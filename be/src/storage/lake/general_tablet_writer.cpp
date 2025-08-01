// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "storage/lake/general_tablet_writer.h"

#include <fmt/format.h>

#include "column/chunk.h"
#include "common/config.h"
#include "fs/bundle_file.h"
#include "fs/fs_util.h"
#include "fs/key_cache.h"
#include "runtime/current_thread.h"
#include "serde/column_array_serde.h"
#include "storage/lake/filenames.h"
#include "storage/lake/tablet_manager.h"
#include "storage/lake/vacuum.h"
#include "storage/rowset/segment_writer.h"
#include "util/threadpool.h"

namespace starrocks::lake {

void collect_writer_stats(OlapWriterStatistics& writer_stats, SegmentWriter* segment_writer) {
    if (segment_writer == nullptr) {
        return;
    }
    auto stats_or = segment_writer->get_numeric_statistics();
    if (!stats_or.ok()) {
        VLOG(3) << "failed to get statistics: " << stats_or.status();
        return;
    }

    std::unique_ptr<io::NumericStatistics> stats = std::move(stats_or).value();
    for (int64_t i = 0, sz = (stats ? stats->size() : 0); i < sz; ++i) {
        auto&& name = stats->name(i);
        auto&& value = stats->value(i);
        if (name == kBytesWriteRemote) {
            writer_stats.bytes_write_remote += value;
        } else if (name == kIONsWriteRemote) {
            writer_stats.write_remote_ns += value;
        }
    }
}

HorizontalGeneralTabletWriter::HorizontalGeneralTabletWriter(TabletManager* tablet_mgr, int64_t tablet_id,
                                                             std::shared_ptr<const TabletSchema> schema, int64_t txn_id,
                                                             bool is_compaction, ThreadPool* flush_pool,
                                                             BundleWritableFileContext* bundle_file_context,
                                                             GlobalDictByNameMaps* global_dicts)
        : TabletWriter(tablet_mgr, tablet_id, std::move(schema), txn_id, is_compaction, flush_pool),
          _bundle_file_context(bundle_file_context),
          _global_dicts(global_dicts) {}

HorizontalGeneralTabletWriter::~HorizontalGeneralTabletWriter() = default;

// To developers: Do NOT perform any I/O in this method, because this method may be invoked
// in a bthread.
Status HorizontalGeneralTabletWriter::open() {
    return Status::OK();
}

Status HorizontalGeneralTabletWriter::write(const starrocks::Chunk& data, SegmentPB* segment, bool eos) {
    if (_seg_writer == nullptr ||
        (_auto_flush && (_seg_writer->estimate_segment_size() >= config::max_segment_file_size ||
                         _seg_writer->num_rows_written() + data.num_rows() >= INT32_MAX /*TODO: configurable*/))) {
        RETURN_IF_ERROR(flush_segment_writer(segment));
        RETURN_IF_ERROR(reset_segment_writer(eos));
    }
    RETURN_IF_ERROR(_seg_writer->append_chunk(data));
    _num_rows += data.num_rows();
    return Status::OK();
}

Status HorizontalGeneralTabletWriter::flush(SegmentPB* segment) {
    return flush_segment_writer(segment);
}

Status HorizontalGeneralTabletWriter::finish(SegmentPB* segment) {
    RETURN_IF_ERROR(flush_segment_writer(segment));
    _finished = true;
    return Status::OK();
}

void HorizontalGeneralTabletWriter::close() {
    if (!_finished && !_files.empty()) {
        std::vector<std::string> full_paths_to_delete;
        full_paths_to_delete.reserve(_files.size());
        for (const auto& f : _files) {
            std::string path;
            if (_location_provider) {
                path = _location_provider->segment_location(_tablet_id, f.path);
            } else {
                path = _tablet_mgr->segment_location(_tablet_id, f.path);
            }
            full_paths_to_delete.emplace_back(path);
        }
        delete_files_async(std::move(full_paths_to_delete));
    }
    _files.clear();
}

Status HorizontalGeneralTabletWriter::reset_segment_writer(bool eos) {
    DCHECK(_schema != nullptr);
    auto name = gen_segment_filename(_txn_id);
    SegmentWriterOptions opts;
    opts.is_compaction = _is_compaction;
    opts.global_dicts = _global_dicts;
    WritableFileOptions wopts;
    if (config::enable_transparent_data_encryption) {
        ASSIGN_OR_RETURN(auto pair, KeyCache::instance().create_encryption_meta_pair_using_current_kek());
        wopts.encryption_info = pair.info;
        opts.encryption_meta = std::move(pair.encryption_meta);
    }
    std::unique_ptr<WritableFile> of;
    auto create_file_fn = [&]() {
        if (_location_provider && _fs) {
            return _fs->new_writable_file(wopts, _location_provider->segment_location(_tablet_id, name));
        } else {
            return fs::new_writable_file(wopts, _tablet_mgr->segment_location(_tablet_id, name));
        }
    };
    if (_bundle_file_context != nullptr && _files.empty() && eos) {
        // If this is the first data file writer and it is the end of stream,
        // then we will create a shared file for this segment writer.
        RETURN_IF_ERROR(_bundle_file_context->try_create_bundle_file(create_file_fn));
        of = std::make_unique<BundleWritableFile>(_bundle_file_context, wopts.encryption_info);
    } else {
        ASSIGN_OR_RETURN(of, create_file_fn());
    }
    auto w = std::make_unique<SegmentWriter>(std::move(of), _seg_id++, _schema, opts);
    RETURN_IF_ERROR(w->init());
    _seg_writer = std::move(w);
    return Status::OK();
}

Status HorizontalGeneralTabletWriter::flush_segment_writer(SegmentPB* segment) {
    if (_seg_writer != nullptr) {
        uint64_t segment_size = 0;
        uint64_t index_size = 0;
        uint64_t footer_position = 0;
        RETURN_IF_ERROR(_seg_writer->finalize(&segment_size, &index_size, &footer_position));
        const std::string& segment_path = _seg_writer->segment_path();
        std::string segment_name = std::string(basename(segment_path));
        auto file_info = FileInfo{segment_name, segment_size, _seg_writer->encryption_meta()};
        if (_seg_writer->bundle_file_offset() >= 0) {
            // This is a bundle data file.
            file_info.bundle_file_offset = _seg_writer->bundle_file_offset();
        }
        _files.emplace_back(file_info);
        _data_size += segment_size;
        collect_writer_stats(_stats, _seg_writer.get());
        _stats.segment_count++;
        if (segment) {
            segment->set_data_size(segment_size);
            segment->set_index_size(index_size);
            segment->set_path(segment_path);
            segment->set_encryption_meta(_seg_writer->encryption_meta());
        }
        const auto& seg_global_dict_columns_valid_info = _seg_writer->global_dict_columns_valid_info();
        for (const auto& it : seg_global_dict_columns_valid_info) {
            if (!it.second) {
                _global_dict_columns_valid_info[it.first] = false;
            } else {
                if (const auto& iter = _global_dict_columns_valid_info.find(it.first);
                    iter == _global_dict_columns_valid_info.end()) {
                    _global_dict_columns_valid_info[it.first] = true;
                }
            }
        }

        _seg_writer.reset();
    }
    return Status::OK();
}

VerticalGeneralTabletWriter::VerticalGeneralTabletWriter(TabletManager* tablet_mgr, int64_t tablet_id,
                                                         std::shared_ptr<const TabletSchema> schema, int64_t txn_id,
                                                         uint32_t max_rows_per_segment, bool is_compaction,
                                                         ThreadPool* flush_pool)
        : TabletWriter(tablet_mgr, tablet_id, std::move(schema), txn_id, is_compaction, flush_pool),
          _max_rows_per_segment(max_rows_per_segment) {}

VerticalGeneralTabletWriter::~VerticalGeneralTabletWriter() {
    auto st = wait_futures_finish();
    if (!st.ok()) {
        LOG(WARNING) << "Fail to finalize segment, tablet_id: " << _tablet_id << ", txn_id: " << _txn_id
                     << ", status:" << st;
    }
}

// To developers: Do NOT perform any I/O in this method, because this method may be invoked
// in a bthread.
Status VerticalGeneralTabletWriter::open() {
    if (_flush_pool != nullptr) {
        // Use CONCURRENT mode to ensure segments can finalize in parallel
        _segment_writer_finalize_token =
                std::make_unique<ConcurrencyLimitedThreadPoolToken>(_flush_pool, _flush_pool->max_threads() * 2);
    }
    return Status::OK();
}

Status VerticalGeneralTabletWriter::write_columns(const Chunk& data, const std::vector<uint32_t>& column_indexes,
                                                  bool is_key) {
    const size_t chunk_num_rows = data.num_rows();
    if (_segment_writers.empty()) {
        DCHECK(is_key);
        auto segment_writer = create_segment_writer(column_indexes, is_key);
        if (!segment_writer.ok()) return segment_writer.status();
        _segment_writers.emplace_back(std::move(segment_writer).value());
        _current_writer_index = 0;
        RETURN_IF_ERROR(_segment_writers[_current_writer_index]->append_chunk(data));
    } else if (is_key) {
        // key columns
        if (_segment_writers[_current_writer_index]->num_rows_written() + chunk_num_rows >= _max_rows_per_segment) {
            RETURN_IF_ERROR(flush_columns(_segment_writers[_current_writer_index]));
            auto segment_writer = create_segment_writer(column_indexes, is_key);
            if (!segment_writer.ok()) return segment_writer.status();
            _segment_writers.emplace_back(std::move(segment_writer).value());
            ++_current_writer_index;
        }
        RETURN_IF_ERROR(_segment_writers[_current_writer_index]->append_chunk(data));
    } else {
        // non key columns
        uint32_t num_rows_written = _segment_writers[_current_writer_index]->num_rows_written();
        uint32_t segment_num_rows = _segment_writers[_current_writer_index]->num_rows();
        DCHECK_LE(num_rows_written, segment_num_rows);

        if (_current_writer_index == 0 && num_rows_written == 0) {
            RETURN_IF_ERROR(_segment_writers[_current_writer_index]->init(column_indexes, is_key));
        }

        if (num_rows_written + chunk_num_rows <= segment_num_rows) {
            RETURN_IF_ERROR(_segment_writers[_current_writer_index]->append_chunk(data));
        } else {
            // split into multi chunks and write into multi segments
            auto write_chunk = data.clone_empty();
            size_t num_left_rows = chunk_num_rows;
            size_t offset = 0;
            while (num_left_rows > 0) {
                if (segment_num_rows == num_rows_written) {
                    RETURN_IF_ERROR(flush_columns(_segment_writers[_current_writer_index]));
                    ++_current_writer_index;
                    RETURN_IF_ERROR(_segment_writers[_current_writer_index]->init(column_indexes, is_key));
                    num_rows_written = _segment_writers[_current_writer_index]->num_rows_written();
                    segment_num_rows = _segment_writers[_current_writer_index]->num_rows();
                }

                size_t write_size = segment_num_rows - num_rows_written;
                if (write_size > num_left_rows) {
                    write_size = num_left_rows;
                }
                write_chunk->append(data, offset, write_size);
                RETURN_IF_ERROR(_segment_writers[_current_writer_index]->append_chunk(*write_chunk));
                write_chunk->reset();
                num_left_rows -= write_size;
                offset += write_size;
                num_rows_written = _segment_writers[_current_writer_index]->num_rows_written();
            }
            DCHECK_EQ(0, num_left_rows);
            DCHECK_EQ(offset, chunk_num_rows);
        }
    }

    if (is_key) {
        _num_rows += chunk_num_rows;
    }
    return Status::OK();
}

Status VerticalGeneralTabletWriter::flush(SegmentPB* segment) {
    return Status::OK();
}

Status VerticalGeneralTabletWriter::flush_columns() {
    if (_segment_writers.empty()) {
        return Status::OK();
    }

    DCHECK(_segment_writers[_current_writer_index]);
    RETURN_IF_ERROR(flush_columns(_segment_writers[_current_writer_index]));
    _current_writer_index = 0;

    if (_segment_writer_finalize_token != nullptr) {
        return wait_futures_finish();
    }
    return Status::OK();
}

Status VerticalGeneralTabletWriter::finish(SegmentPB* segment) {
    for (auto& segment_writer : _segment_writers) {
        uint64_t segment_size = 0;
        uint64_t footer_position = 0;
        RETURN_IF_ERROR(segment_writer->finalize_footer(&segment_size, &footer_position));
        const std::string& segment_path = segment_writer->segment_path();
        std::string segment_name = std::string(basename(segment_path));
        _files.emplace_back(FileInfo{segment_name, segment_size, segment_writer->encryption_meta()});
        _data_size += segment_size;
        collect_writer_stats(_stats, segment_writer.get());
        _stats.segment_count++;
        segment_writer.reset();
    }
    _segment_writers.clear();
    if (_segment_writer_finalize_token != nullptr) {
        _segment_writer_finalize_token.reset();
    }
    _finished = true;
    return Status::OK();
}

void VerticalGeneralTabletWriter::close() {
    if (!_finished && !_files.empty()) {
        std::vector<std::string> full_paths_to_delete;
        full_paths_to_delete.reserve(_files.size());
        for (const auto& f : _files) {
            std::string path;
            if (_location_provider) {
                path = _location_provider->segment_location(_tablet_id, f.path);
            } else {
                path = _tablet_mgr->segment_location(_tablet_id, f.path);
            }
            full_paths_to_delete.emplace_back(path);
        }
        delete_files_async(std::move(full_paths_to_delete));
    }
    _files.clear();
}

StatusOr<std::shared_ptr<SegmentWriter>> VerticalGeneralTabletWriter::create_segment_writer(
        const std::vector<uint32_t>& column_indexes, bool is_key) {
    DCHECK(_schema != nullptr);
    auto name = gen_segment_filename(_txn_id);
    SegmentWriterOptions opts;
    opts.is_compaction = _is_compaction;
    WritableFileOptions wopts;
    if (config::enable_transparent_data_encryption) {
        ASSIGN_OR_RETURN(auto pair, KeyCache::instance().create_encryption_meta_pair_using_current_kek());
        wopts.encryption_info = pair.info;
        opts.encryption_meta = std::move(pair.encryption_meta);
    }
    std::unique_ptr<WritableFile> of;
    if (_location_provider && _fs) {
        ASSIGN_OR_RETURN(of, _fs->new_writable_file(wopts, _location_provider->segment_location(_tablet_id, name)));
    } else {
        ASSIGN_OR_RETURN(of, fs::new_writable_file(wopts, _tablet_mgr->segment_location(_tablet_id, name)));
    }
    auto w = std::make_shared<SegmentWriter>(std::move(of), _seg_id++, _schema, opts);
    RETURN_IF_ERROR(w->init(column_indexes, is_key));
    return w;
}

Status VerticalGeneralTabletWriter::flush_columns(const std::shared_ptr<SegmentWriter>& segment_writer) {
    if (_segment_writer_finalize_token != nullptr) {
        auto status = check_futures();
        if (!status.ok()) {
            return status;
        }
        auto mem_tracker = tls_thread_status.mem_tracker();
        auto task = std::make_shared<std::packaged_task<Status()>>([segment_writer, mem_tracker]() {
            SCOPED_THREAD_LOCAL_MEM_TRACKER_SETTER(mem_tracker);
            uint64_t index_size = 0;
            return segment_writer->finalize_columns(&index_size);
        });
        auto packaged_func = [task]() { (*task)(); };
        auto timeout_deadline =
                std::chrono::system_clock::now() + std::chrono::milliseconds(kDefaultTimeoutForAsyncWriteSegment);
        auto st = _segment_writer_finalize_token->submit_func(std::move(packaged_func), timeout_deadline);
        if (!st.ok()) {
            LOG(WARNING) << "Fail to submit segment writer finalizing task to thread pool, " << st;
            return st;
        }
        _futures.push_back(task->get_future());
    } else {
        uint64_t index_size = 0;
        RETURN_IF_ERROR(segment_writer->finalize_columns(&index_size));
    }
    return Status::OK();
}

template <typename R>
bool is_ready(std::future<R> const& f) {
    return f.wait_for(std::chrono::seconds(0)) == std::future_status::ready;
}

Status VerticalGeneralTabletWriter::check_futures() {
    for (auto it = _futures.begin(); it != _futures.end();) {
        if (is_ready(*it)) {
            auto st = it->get();
            if (!st.ok()) {
                LOG(WARNING) << "Segment flushing task resulted in error: " << st;
                return st;
            }
            it = _futures.erase(it);
        } else {
            ++it;
        }
    }
    return Status::OK();
}

Status VerticalGeneralTabletWriter::wait_futures_finish() {
    Status ret = Status::OK();
    for (auto& future : _futures) {
        if (auto st = future.get(); !st.ok()) {
            VLOG(3) << "Fail to finalize segment, " << st;
            ret.update(st);
        }
    }
    _futures.clear();
    return ret;
}

} // namespace starrocks::lake
