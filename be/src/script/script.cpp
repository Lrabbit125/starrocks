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

#include "script/script.h"

#include <google/protobuf/util/json_util.h>

#include <regex>

#include "common/greplog.h"
#include "common/logging.h"
#include "common/prof/heap_prof.h"
#include "common/vlog_cntl.h"
#include "exec/schema_scanner/schema_be_tablets_scanner.h"
#include "fs/key_cache.h"
#include "gen_cpp/olap_file.pb.h"
#include "gutil/strings/substitute.h"
#include "http/action/compaction_action.h"
#include "io/io_profiler.h"
#include "runtime/exec_env.h"
#include "runtime/mem_tracker.h"
#include "storage/del_vector.h"
#include "storage/lake/tablet.h"
#include "storage/lake/tablet_manager.h"
#include "storage/lake/tablet_metadata.h"
#include "storage/lake/vacuum.h"
#include "storage/primary_key_dump.h"
#include "storage/storage_engine.h"
#include "storage/tablet.h"
#include "storage/tablet_manager.h"
#include "storage/tablet_meta_manager.h"
#include "storage/tablet_updates.h"
#include "util/stack_util.h"
#include "util/url_coding.h"
#include "wrenbind17/wrenbind17.hpp"

using namespace wrenbind17;
using std::string;

namespace starrocks {

extern std::vector<std::string> list_stack_trace_of_long_wait_mutex();

#define REG_VAR(TYPE, NAME) cls.var<&TYPE::NAME>(#NAME)
#define REG_METHOD(TYPE, NAME) cls.func<&TYPE::NAME>(#NAME)
#define REG_STATIC_METHOD(TYPE, NAME) cls.funcStatic<&TYPE::NAME>(#NAME)

template <class T>
std::string proto_to_json(T& proto) {
    std::string json;
    google::protobuf::util::MessageToJsonString(proto, &json);
    return json;
}

static std::shared_ptr<TabletUpdatesPB> tablet_updates_to_pb(TabletUpdates& self) {
    std::shared_ptr<TabletUpdatesPB> pb = std::make_shared<TabletUpdatesPB>();
    self.to_updates_pb(pb.get());
    return pb;
}

static EditVersionMetaPB* tablet_updates_pb_version(TabletUpdatesPB& self, int idx) {
    if (idx < 0 || idx >= self.versions_size()) return nullptr;
    return self.mutable_versions(idx);
}

static uint64_t kv_store_get_live_sst_files_size(KVStore& store) {
    uint64_t ret = 0;
    store.get_live_sst_files_size(&ret);
    return ret;
}

static int tablet_keys_type_int(Tablet& tablet) {
    return static_cast<int>(tablet.keys_type());
}

static int tablet_tablet_state(Tablet& tablet) {
    return static_cast<int>(tablet.tablet_state());
}

static std::string tablet_set_tablet_state(Tablet& tablet, int state) {
    return tablet.set_tablet_state(static_cast<TabletState>(state)).to_string();
}

static const TabletSchema& tablet_tablet_schema(Tablet& tablet) {
    return tablet.unsafe_tablet_schema_ref();
}

static uint64_t tablet_tablet_id(Tablet& tablet) {
    return tablet.tablet_id();
}

static std::string tablet_path(Tablet& tablet) {
    return tablet.schema_hash_path();
}

static DataDir* tablet_data_dir(Tablet& tablet) {
    return tablet.data_dir();
}

static uint64_t get_major_number(EditVersion& self) {
    return self.major_number();
}

static uint64_t get_minor_number(EditVersion& self) {
    return self.minor_number();
}

static void bind_common(ForeignModule& m) {
    {
        auto& cls = m.klass<Status>("Status");
        cls.func<&Status::to_string>("toString");
        REG_METHOD(Status, ok);
    }
}

std::string memtracker_debug_string(MemTracker& self) {
    return self.debug_string();
}

static std::vector<FileWriteStat> get_file_write_history() {
    std::vector<FileWriteStat> stats;
    FileSystem::get_file_write_history(&stats);
    return stats;
}

static int64_t unix_seconds() {
    return UnixSeconds();
}

std::string exec(const std::string& cmd) {
    std::string ret;

    FILE* fp = popen(cmd.c_str(), "r");
    if (fp == nullptr) {
        ret = strings::Substitute("popen failed: $0 cmd: $1", strerror(errno), cmd);
        return ret;
    }

    char buff[4096];
    while (true) {
        size_t r = fread(buff, 1, 4096, fp);
        if (r == 0) {
            break;
        }
        ret.append(buff, r);
    }
    int status = pclose(fp);
    if (status == -1) {
        ret.append(strings::Substitute("pclose failed: $0", strerror(errno)));
    } else if (status != 0) {
        ret.append(strings::Substitute("exit: $0", status));
    }
    return ret;
}

static std::string exec_whitelist(const std::string& cmd) {
    static std::regex legal_cmd(R"((ls|cat|head|tail|grep|free|echo)[^<>\|;`\\]*)");
    std::cmatch m;
    if (!std::regex_match(cmd.c_str(), m, legal_cmd)) {
        return "illegal cmd";
    }
    return exec(cmd);
}

static std::string io_profile_and_get_topn_stats(const std::string& mode, int seconds, size_t topn) {
    return IOProfiler::profile_and_get_topn_stats_str(mode, seconds, topn);
}

static std::string key_cache_info() {
    return KeyCache::instance().to_string();
}

void bind_exec_env(ForeignModule& m) {
    {
        auto& cls = m.klass<MemTracker>("MemTracker");
        REG_METHOD(MemTracker, label);
        REG_METHOD(MemTracker, limit);
        REG_METHOD(MemTracker, consumption);
        REG_METHOD(MemTracker, peak_consumption);
        REG_METHOD(MemTracker, parent);
        cls.funcExt<&memtracker_debug_string>("toString");
    }
    {
        auto& cls = m.klass<FileWriteStat>("FileWriteStat");
        REG_VAR(FileWriteStat, open_time);
        REG_VAR(FileWriteStat, close_time);
        REG_VAR(FileWriteStat, path);
        REG_VAR(FileWriteStat, size);
    }
    {
        auto& cls = m.klass<ExecEnv>("ExecEnv");
        REG_STATIC_METHOD(ExecEnv, GetInstance);
        cls.funcStaticExt<&get_thread_id_list>("get_thread_id_list");
        cls.funcStaticExt<&get_stack_trace_for_thread>("get_stack_trace_for_thread");
        cls.funcStaticExt<&get_stack_trace_for_threads>("get_stack_trace_for_threads");
        cls.funcStaticExt<&get_stack_trace_for_all_threads>("get_stack_trace_for_all_threads");
        cls.funcStaticExt<&get_stack_trace_for_function>("get_stack_trace_for_function");
        cls.funcStaticExt<&io_profile_and_get_topn_stats>("io_profile_and_get_topn_stats");
        cls.funcStaticExt<&grep_log_as_string>("grep_log_as_string");
        cls.funcStaticExt<&get_file_write_history>("get_file_write_history");
        cls.funcStaticExt<&unix_seconds>("unix_seconds");
        // uncomment this to enable executing shell commands
        // cls.funcStaticExt<&exec_whitelist>("exec");
        cls.funcStaticExt<&list_stack_trace_of_long_wait_mutex>("list_stack_trace_of_long_wait_mutex");
        cls.funcStaticExt<&key_cache_info>("key_cache_info");
    }
    {
        auto& cls = m.klass<GlobalEnv>("GlobalEnv");
        REG_STATIC_METHOD(GlobalEnv, GetInstance);

        // level 0
        REG_METHOD(GlobalEnv, process_mem_tracker);

        // level 1
        REG_METHOD(GlobalEnv, query_pool_mem_tracker);
        REG_METHOD(GlobalEnv, load_mem_tracker);
        REG_METHOD(GlobalEnv, metadata_mem_tracker);
        REG_METHOD(GlobalEnv, compaction_mem_tracker);
        REG_METHOD(GlobalEnv, schema_change_mem_tracker);
        REG_METHOD(GlobalEnv, page_cache_mem_tracker);
        REG_METHOD(GlobalEnv, jit_cache_mem_tracker);
        REG_METHOD(GlobalEnv, update_mem_tracker);
        REG_METHOD(GlobalEnv, passthrough_mem_tracker);
        REG_METHOD(GlobalEnv, clone_mem_tracker);
        REG_METHOD(GlobalEnv, consistency_mem_tracker);
        REG_METHOD(GlobalEnv, connector_scan_pool_mem_tracker);
        REG_METHOD(GlobalEnv, datacache_mem_tracker);

        // level 2
        REG_METHOD(GlobalEnv, tablet_metadata_mem_tracker);
        REG_METHOD(GlobalEnv, rowset_metadata_mem_tracker);
        REG_METHOD(GlobalEnv, segment_metadata_mem_tracker);
        REG_METHOD(GlobalEnv, column_metadata_mem_tracker);

        // level 3
        REG_METHOD(GlobalEnv, tablet_schema_mem_tracker);
        REG_METHOD(GlobalEnv, column_zonemap_index_mem_tracker);
        REG_METHOD(GlobalEnv, ordinal_index_mem_tracker);
        REG_METHOD(GlobalEnv, bitmap_index_mem_tracker);
        REG_METHOD(GlobalEnv, bloom_filter_index_mem_tracker);
        REG_METHOD(GlobalEnv, segment_zonemap_mem_tracker);
        REG_METHOD(GlobalEnv, short_key_index_mem_tracker);
    }
    {
        auto& cls = m.klass<HeapProf>("HeapProf");
        REG_STATIC_METHOD(HeapProf, getInstance);
        REG_METHOD(HeapProf, enable_prof);
        REG_METHOD(HeapProf, disable_prof);
        REG_METHOD(HeapProf, has_enable);
        REG_METHOD(HeapProf, snapshot);
        REG_METHOD(HeapProf, to_dot_format);
        REG_METHOD(HeapProf, dump_dot_snapshot);
    }
    {
        auto& cls = m.klass<VLogCntl>("VLogCntl");
        REG_STATIC_METHOD(VLogCntl, getInstance);
        REG_METHOD(VLogCntl, enable);
        REG_METHOD(VLogCntl, disable);
        REG_METHOD(VLogCntl, setLogLevel);
    }
}

class StorageEngineRef {
public:
    static string drop_tablet(int64_t tablet_id) {
        auto manager = StorageEngine::instance()->tablet_manager();
        string err;
        auto ptr = manager->get_tablet(tablet_id, true, &err);
        if (ptr == nullptr) {
            return strings::Substitute("get tablet $0 failed: $1", tablet_id, err);
        }
        auto st = manager->drop_tablet(tablet_id, TabletDropFlag::kKeepMetaAndFiles);
        return st.to_string();
    }

    static std::shared_ptr<Tablet> get_tablet(int64_t tablet_id) {
        string err;
        auto ptr = StorageEngine::instance()->tablet_manager()->get_tablet(tablet_id, true, &err);
        if (ptr == nullptr) {
            LOG(WARNING) << "get_tablet " << tablet_id << " failed: " << err;
            return nullptr;
        }
        return ptr;
    }

    static std::string get_lake_tablet_metadata_json(int64_t tablet_id, int64_t version) {
        auto tablet_manager = ExecEnv::GetInstance()->lake_tablet_manager();
        RETURN_IF(nullptr == tablet_manager, "");
        auto meta_st = tablet_manager->get_tablet_metadata(tablet_id, version, false);
        RETURN_IF(!meta_st.ok(), meta_st.status().to_string());
        return proto_to_json(*meta_st.value());
    }

    static std::string decode_encryption_meta(const std::string& meta_base64) {
        EncryptionMetaPB pb;
        std::string meta_bytes;
        RETURN_IF(!base64_decode(meta_base64, &meta_bytes), "bad base64 string");
        RETURN_IF(!pb.ParseFromString(meta_bytes), "parse encryption meta failed");
        return proto_to_json(pb);
    }

    static std::string garbage_file_check(const std::string& root_location) {
        auto val_st = lake::garbage_file_check(root_location);
        if (!val_st.ok()) {
            LOG(WARNING) << "garbage_file_check failed: " << val_st.status().to_string();
            // return empty string to indicate failure
            return "";
        }
        return std::to_string(val_st.value());
    }

    static std::shared_ptr<TabletBasicInfo> get_tablet_info(int64_t tablet_id) {
        std::vector<TabletBasicInfo> tablet_infos;
        auto manager = StorageEngine::instance()->tablet_manager();
        manager->get_tablets_basic_infos(-1, -1, tablet_id, tablet_infos, nullptr);
        if (tablet_infos.empty()) {
            return nullptr;
        } else {
            return std::make_shared<TabletBasicInfo>(tablet_infos[0]);
        }
    }

    static std::vector<TabletBasicInfo> get_tablet_infos(int64_t table_id, int64_t partition_id) {
        std::vector<TabletBasicInfo> tablet_infos;
        auto manager = StorageEngine::instance()->tablet_manager();
        manager->get_tablets_basic_infos(table_id, partition_id, -1, tablet_infos, nullptr);
        return tablet_infos;
    }

    static std::vector<DataDir*> get_data_dirs() { return StorageEngine::instance()->get_stores(); }

    /**
     * @param tablet_id
     * @param type base|cumulative|update
     * @return
     */
    static Status do_compaction(int64_t tablet_id, const string& type) {
        return CompactionAction::do_compaction(tablet_id, type, "");
    }

    static std::string set_error_state(int64_t tablet_id) {
        auto tablet = get_tablet(tablet_id);
        if (!tablet) {
            return "tablet not found";
        }
        if (tablet->updates() == nullptr) {
            return "not support set error state";
        }
        tablet->updates()->set_error("error by script");
        return "set error state success";
    }

    static std::string recover_tablet(int64_t tablet_id) {
        auto tablet = get_tablet(tablet_id);
        if (!tablet) {
            return "tablet not found";
        }
        if (tablet->updates() == nullptr) {
            return "not support recover";
        }
        Status st = tablet->updates()->recover();
        return strings::Substitute("recover tablet:$0 status:$1", std::to_string(tablet_id), st.message());
    }

    static std::string get_tablet_meta_json(int64_t tablet_id) {
        auto tablet = get_tablet(tablet_id);
        if (!tablet) {
            return "tablet not found";
        }
        std::string ret;
        auto st = TabletMetaManager::get_json_meta(tablet->data_dir(), tablet->tablet_id(), &ret);
        if (!st.ok()) {
            return st.to_string();
        } else {
            return ret;
        }
    }

    // this method is specifically used to recover "no delete vector found" error caused by corrupt pk tablet metadata
    static std::string reset_delvec(int64_t tablet_id, int64_t segment_id, int64_t version) {
        auto tablet = get_tablet(tablet_id);
        RETURN_IF_UNLIKELY_NULL(tablet, "tablet not found");
        DelVector dv;
        dv.init(version, nullptr, 0);
        auto st = TabletMetaManager::set_del_vector(tablet->data_dir()->get_meta(), tablet_id, segment_id, dv);
        return st.to_string();
    }

    static size_t submit_manual_compaction_task_for_table(int64_t table_id, int64_t rowset_size_threshold) {
        auto infos = get_tablet_infos(table_id, -1);
        for (auto& info : infos) {
            submit_manual_compaction_task_for_tablet(info.tablet_id, rowset_size_threshold);
        }
        return infos.size();
    }

    static size_t submit_manual_compaction_task_for_partition(int64_t partition_id, int64_t rowset_size_threshold) {
        auto infos = get_tablet_infos(-1, partition_id);
        for (auto& info : infos) {
            submit_manual_compaction_task_for_tablet(info.tablet_id, rowset_size_threshold);
        }
        return infos.size();
    }

    static void submit_manual_compaction_task_for_tablet(int64_t tablet_id, int64_t rowset_size_threshold) {
        StorageEngine::instance()->submit_manual_compaction_task(tablet_id, rowset_size_threshold);
    }

    static std::string get_manual_compaction_status() {
        return StorageEngine::instance()->get_manual_compaction_status();
    }

    static std::string ls_tablet_dir(int64_t tablet_id) {
        auto tablet = get_tablet(tablet_id);
        if (!tablet) {
            return "tablet not found";
        }
        return exec_whitelist(strings::Substitute("ls -al $0", tablet->schema_hash_path()));
    }

    static std::string pk_dump(int64_t tablet_id) {
        auto tablet = get_tablet(tablet_id);
        if (!tablet) {
            return "tablet not found";
        }
        if (tablet->updates() == nullptr) {
            return "non-pk tablet no support set error";
        }
        PrimaryKeyDump pkd(tablet.get());
        auto st = pkd.dump();
        if (st.ok()) {
            return "print primary key dump success";
        } else {
            LOG(ERROR) << "print primary key dump fail, " << st;
            return "print primary key dump fail";
        }
    }

    static void bind(ForeignModule& m) {
        {
            auto& cls = m.klass<TabletBasicInfo>("TabletBasicInfo");
            REG_VAR(TabletBasicInfo, table_id);
            REG_VAR(TabletBasicInfo, partition_id);
            REG_VAR(TabletBasicInfo, tablet_id);
            REG_VAR(TabletBasicInfo, num_version);
            REG_VAR(TabletBasicInfo, max_version);
            REG_VAR(TabletBasicInfo, min_version);
            REG_VAR(TabletBasicInfo, num_rowset);
            REG_VAR(TabletBasicInfo, num_row);
            REG_VAR(TabletBasicInfo, data_size);
            REG_VAR(TabletBasicInfo, index_mem);
            REG_VAR(TabletBasicInfo, create_time);
            REG_VAR(TabletBasicInfo, state);
            REG_VAR(TabletBasicInfo, type);
            REG_VAR(TabletBasicInfo, data_dir);
            REG_VAR(TabletBasicInfo, shard_id);
            REG_VAR(TabletBasicInfo, schema_hash);
        }
        {
            auto& cls = m.klass<TabletSchema>("TabletSchema");
            REG_METHOD(TabletSchema, num_columns);
            REG_METHOD(TabletSchema, num_key_columns);
            REG_METHOD(TabletSchema, keys_type);
            REG_METHOD(TabletSchema, mem_usage);
            cls.func<&TabletSchema::debug_string>("toString");
        }
        {
            auto& cls = m.klass<Tablet>("Tablet");
            cls.funcExt<tablet_tablet_id>("tablet_id");
            cls.funcExt<tablet_tablet_schema>("schema");
            cls.funcExt<tablet_path>("path");
            cls.funcExt<tablet_data_dir>("data_dir");
            cls.funcExt<tablet_keys_type_int>("keys_type_as_int");
            cls.funcExt<tablet_tablet_state>("tablet_state_as_int");
            cls.funcExt<tablet_set_tablet_state>("set_tablet_state_as_int");
            REG_METHOD(Tablet, tablet_footprint);
            REG_METHOD(Tablet, num_rows);
            REG_METHOD(Tablet, version_count);
            REG_METHOD(Tablet, max_version);
            REG_METHOD(Tablet, max_continuous_version);
            REG_METHOD(Tablet, compaction_score);
            REG_METHOD(Tablet, schema_debug_string);
            REG_METHOD(Tablet, debug_string);
            REG_METHOD(Tablet, support_binlog);
            REG_METHOD(Tablet, updates);
            REG_METHOD(Tablet, save_meta);
            REG_METHOD(Tablet, verify);
        }
        {
            auto& cls = m.klass<EditVersionPB>("EditVersionPB");
            cls.funcExt<&proto_to_json<EditVersionPB>>("toString");
        }
        {
            auto& cls = m.klass<EditVersionMetaPB>("EditVersionMetaPB");
            REG_METHOD(EditVersionMetaPB, version);
            REG_METHOD(EditVersionMetaPB, creation_time);
            cls.funcExt<&proto_to_json<EditVersionMetaPB>>("toString");
        }
        {
            auto& cls = m.klass<TabletUpdatesPB>("TabletUpdatesPB");
            REG_METHOD(TabletUpdatesPB, versions_size);
            cls.funcExt<&tablet_updates_pb_version>("versions");
            REG_METHOD(TabletUpdatesPB, apply_version);
            REG_METHOD(TabletUpdatesPB, next_rowset_id);
            REG_METHOD(TabletUpdatesPB, next_log_id);
            cls.funcExt<&proto_to_json<TabletUpdatesPB>>("toString");
        }
        {
            auto& cls = m.klass<EditVersion>("EditVersion");
            cls.funcExt<&get_major_number>("major_number");
            cls.funcExt<&get_minor_number>("minor_number");
            cls.func<&EditVersion::to_string>("toString");
        }
        {
            auto& cls = m.klass<CompactionInfo>("CompactionInfo");
            REG_VAR(CompactionInfo, start_version);
            REG_VAR(CompactionInfo, inputs);
            REG_VAR(CompactionInfo, output);
        }
        {
            auto& cls = m.klass<EditVersionInfo>("EditVersionInfo");
            REG_VAR(EditVersionInfo, version);
            REG_VAR(EditVersionInfo, creation_time);
            REG_VAR(EditVersionInfo, rowsets);
            REG_VAR(EditVersionInfo, deltas);
            REG_VAR(EditVersionInfo, gtid);
            REG_METHOD(EditVersionInfo, get_compaction);
        }
        {
            auto& cls = m.klass<Rowset>("Rowset");
            REG_METHOD(Rowset, rowset_id_str);
            REG_METHOD(Rowset, schema_ref);
            REG_METHOD(Rowset, start_version);
            REG_METHOD(Rowset, end_version);
            REG_METHOD(Rowset, creation_time);
            REG_METHOD(Rowset, data_disk_size);
            REG_METHOD(Rowset, empty);
            REG_METHOD(Rowset, num_rows);
            REG_METHOD(Rowset, total_row_size);
            REG_METHOD(Rowset, txn_id);
            REG_METHOD(Rowset, partition_id);
            REG_METHOD(Rowset, num_segments);
            REG_METHOD(Rowset, num_delete_files);
            REG_METHOD(Rowset, rowset_path);
        }
        {
            auto& cls = m.klass<TabletUpdates>("TabletUpdates");
            REG_METHOD(TabletUpdates, get_error_msg);
            REG_METHOD(TabletUpdates, num_rows);
            REG_METHOD(TabletUpdates, data_size);
            REG_METHOD(TabletUpdates, num_rowsets);
            REG_METHOD(TabletUpdates, max_version);
            REG_METHOD(TabletUpdates, version_count);
            REG_METHOD(TabletUpdates, num_pending);
            REG_METHOD(TabletUpdates, get_compaction_score);
            REG_METHOD(TabletUpdates, version_history_count);
            REG_METHOD(TabletUpdates, get_average_row_size);
            REG_METHOD(TabletUpdates, debug_string);
            REG_METHOD(TabletUpdates, get_version_list);
            REG_METHOD(TabletUpdates, get_edit_version);
            REG_METHOD(TabletUpdates, get_rowset_map);
            cls.funcExt<&tablet_updates_to_pb>("toPB");
        }
        {
            auto& cls = m.klass<DataDir>("DataDir");
            REG_METHOD(DataDir, path);
            REG_METHOD(DataDir, path_hash);
            REG_METHOD(DataDir, is_used);
            REG_METHOD(DataDir, get_meta);
            REG_METHOD(DataDir, is_used);
            REG_METHOD(DataDir, available_bytes);
            REG_METHOD(DataDir, disk_capacity_bytes);
        }
        {
            auto& cls = m.klass<KVStore>("KVStore");
            REG_METHOD(KVStore, compact);
            REG_METHOD(KVStore, flushMemTable);
            REG_METHOD(KVStore, get_stats);
            cls.funcExt<&kv_store_get_live_sst_files_size>("sst_file_size");
        }
        {
            auto& cls = m.klass<StorageEngineRef>("StorageEngine");
            REG_STATIC_METHOD(StorageEngineRef, get_tablet_info);
            REG_STATIC_METHOD(StorageEngineRef, get_tablet_infos);
            REG_STATIC_METHOD(StorageEngineRef, get_tablet_meta_json);
            REG_STATIC_METHOD(StorageEngineRef, get_lake_tablet_metadata_json);
            REG_STATIC_METHOD(StorageEngineRef, decode_encryption_meta);
            REG_STATIC_METHOD(StorageEngineRef, reset_delvec);
            REG_STATIC_METHOD(StorageEngineRef, get_tablet);
            REG_STATIC_METHOD(StorageEngineRef, drop_tablet);
            REG_STATIC_METHOD(StorageEngineRef, get_data_dirs);
            REG_STATIC_METHOD(StorageEngineRef, do_compaction);
            REG_STATIC_METHOD(StorageEngineRef, submit_manual_compaction_task_for_table);
            REG_STATIC_METHOD(StorageEngineRef, submit_manual_compaction_task_for_partition);
            REG_STATIC_METHOD(StorageEngineRef, submit_manual_compaction_task_for_tablet);
            REG_STATIC_METHOD(StorageEngineRef, get_manual_compaction_status);
            REG_STATIC_METHOD(StorageEngineRef, pk_dump);
            REG_STATIC_METHOD(StorageEngineRef, ls_tablet_dir);
            REG_STATIC_METHOD(StorageEngineRef, set_error_state);
            REG_STATIC_METHOD(StorageEngineRef, recover_tablet);
            REG_STATIC_METHOD(StorageEngineRef, garbage_file_check);
        }
    }
};

Status execute_script(const std::string& script, std::string& output) {
    wrenbind17::VM vm;
    vm.setPrintFunc([&](const char* text) { output.append(text); });
    auto& m = vm.module("starrocks");
    bind_common(m);
    bind_exec_env(m);
    StorageEngineRef::bind(m);
    vm.runFromSource("main", R"(import "starrocks" for ExecEnv, GlobalEnv, HeapProf, StorageEngine, VLogCntl)");
    try {
        vm.runFromSource("main", script);
    } catch (const std::exception& e) {
        output.append(e.what());
    }
    return Status::OK();
}

} // namespace starrocks
