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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/qe/VariableMgr.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.qe;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.gson.annotations.SerializedName;
import com.starrocks.analysis.VariableExpr;
import com.starrocks.catalog.Type;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.PatternMatcher;
import com.starrocks.persist.EditLog;
import com.starrocks.persist.GlobalVarPersistInfo;
import com.starrocks.persist.ImageWriter;
import com.starrocks.persist.metablock.SRMetaBlockEOFException;
import com.starrocks.persist.metablock.SRMetaBlockException;
import com.starrocks.persist.metablock.SRMetaBlockID;
import com.starrocks.persist.metablock.SRMetaBlockReader;
import com.starrocks.persist.metablock.SRMetaBlockWriter;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.SetType;
import com.starrocks.sql.ast.SystemVariable;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.similarity.JaroWinklerDistance;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Variable manager, merge session variable and global variable.
 * <p>
 * There are two types of variables, SESSION and GLOBAL.
 * <p>
 * The GLOBAL variable is more like a system configuration, which takes effect globally.
 * The settings for global variables are global and persistent.
 * After the cluster is restarted, the set values still be restored.
 * The global variables are defined in `GlobalVariable`.
 * The variable of the READ_ONLY attribute cannot be changed,
 * and the variable of the GLOBAL attribute can be changed at runtime.
 * <p>
 * Session variables are session-level, and the scope of these variables is usually
 * in a session connection. The session variables are defined in `SessionVariable`.
 * <p>
 * For the setting of the global variable, the value of the field in the `GlobalVariable` class
 * will be modified directly through the reflection mechanism of Java.
 * <p>
 * For the setting of session variables, there are also two types: Global and Session.
 * <p>
 * 1. Use `set global` comment to set session variables
 * <p>
 * This setting method is equivalent to changing the default value of the session variable.
 * It will modify the `defaultSessionVariable` member.
 * This operation is persistent and global. After the setting is complete, when a new session
 * is established, this default value will be used to generate session-level session variables.
 * This operation will also affect the value of the variable in the current session.
 * <p>
 * 2. Use the `set` comment (no global) to set the session variable
 * <p>
 * This setting method will only change the value of the variable in the current session.
 * After the session ends, this setting will also become invalid.
 */
public class VariableMgr {
    private static final Logger LOG = LogManager.getLogger(VariableMgr.class);

    // variable have this flag means that every session have a copy of this variable,
    // and can modify its own variable.
    public static final int SESSION = 1;
    // Variables with this flag have only one instance in one process.
    public static final int GLOBAL = 1 << 1;
    // Variables with this flag only exist in each session.
    public static final int SESSION_ONLY = 1 << 2;
    // Variables with this flag can only be read.
    public static final int READ_ONLY = 1 << 3;
    // Variables with this flag can not be seen with `SHOW VARIABLES` statement.
    public static final int INVISIBLE = 1 << 4;
    // Variables with this flag will not forward to leader when modified in session
    public static final int DISABLE_FORWARD_TO_LEADER = 1 << 5;

    // Map variable name to variable context which have enough information to change variable value.
    // This map contains info of all session and global variables.
    private final ImmutableMap<String, VarContext> ctxByVarName;

    private final ImmutableMap<String, String> aliases;

    // This variable is equivalent to the default value of session variables.
    // Whenever a new session is established, the value in this object is copied to the session-level variable.
    private final SessionVariable defaultSessionVariable;

    // Global read/write lock to protect access of globalSessionVariable.
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock rLock = rwLock.readLock();
    private final Lock wLock = rwLock.writeLock();

    public VariableMgr() {
        // Session value
        defaultSessionVariable = new SessionVariable();
        ImmutableSortedMap.Builder<String, VarContext> ctxBuilder =
                ImmutableSortedMap.orderedBy(String.CASE_INSENSITIVE_ORDER);
        ImmutableSortedMap.Builder<String, String> aliasBuilder =
                ImmutableSortedMap.orderedBy(String.CASE_INSENSITIVE_ORDER);
        for (Field field : SessionVariable.class.getDeclaredFields()) {
            VarAttr attr = field.getAnnotation(VarAttr.class);
            if (attr == null) {
                continue;
            }

            if (StringUtils.isNotBlank(attr.show())) {
                Preconditions.checkState((attr.show().equals(attr.name()) || attr.show().equals(attr.alias())),
                        "Session variables show is not equal name or alias");
            }

            field.setAccessible(true);
            ctxBuilder.put(attr.name(), new VarContext(field, defaultSessionVariable, SESSION | attr.flag(),
                    getValue(defaultSessionVariable, field), attr));

            if (!attr.alias().isEmpty()) {
                aliasBuilder.put(attr.alias(), attr.name());
            }
        }

        // Variables only exist in global environment.
        for (Field field : GlobalVariable.class.getDeclaredFields()) {
            VarAttr attr = field.getAnnotation(VarAttr.class);
            if (attr == null) {
                continue;
            }

            field.setAccessible(true);
            ctxBuilder.put(attr.name(),
                    new VarContext(field, null, GLOBAL | attr.flag(), getValue(null, field), attr));

            if (!attr.alias().isEmpty()) {
                aliasBuilder.put(attr.alias(), attr.name());
            }
        }

        ctxByVarName = ctxBuilder.build();
        aliases = aliasBuilder.build();
    }

    public SessionVariable getDefaultSessionVariable() {
        return defaultSessionVariable;
    }

    // Set value to a variable
    private boolean setValue(Object obj, Field field, String value) throws DdlException {
        VarAttr attr = field.getAnnotation(VarAttr.class);

        String variableName;
        if (attr.show().isEmpty()) {
            variableName = attr.name();
        } else {
            variableName = attr.show();
        }

        String convertedVal = VariableVarConverters.convert(variableName, value);
        try {
            switch (field.getType().getSimpleName()) {
                case "boolean":
                    if (convertedVal.equalsIgnoreCase("ON")
                            || convertedVal.equalsIgnoreCase("TRUE")
                            || convertedVal.equalsIgnoreCase("1")) {
                        field.setBoolean(obj, true);
                    } else if (convertedVal.equalsIgnoreCase("OFF")
                            || convertedVal.equalsIgnoreCase("FALSE")
                            || convertedVal.equalsIgnoreCase("0")) {
                        field.setBoolean(obj, false);
                    } else {
                        throw new IllegalAccessException();
                    }
                    break;
                case "byte":
                    field.setByte(obj, Byte.parseByte(convertedVal));
                    break;
                case "short":
                    field.setShort(obj, Short.parseShort(convertedVal));
                    break;
                case "int":
                    field.setInt(obj, Integer.parseInt(convertedVal));
                    break;
                case "long":
                    field.setLong(obj, Long.parseLong(convertedVal));
                    break;
                case "float":
                    field.setFloat(obj, Float.parseFloat(convertedVal));
                    break;
                case "double":
                    field.setDouble(obj, Double.parseDouble(convertedVal));
                    break;
                case "String":
                    field.set(obj, convertedVal);
                    break;
                default:
                    // Unsupported type variable.
                    ErrorReport.reportDdlException(ErrorCode.ERR_WRONG_TYPE_FOR_VAR, variableName);
            }
        } catch (NumberFormatException e) {
            ErrorReport.reportDdlException(ErrorCode.ERR_WRONG_TYPE_FOR_VAR, variableName);
        } catch (IllegalAccessException e) {
            ErrorReport.reportDdlException(ErrorCode.ERR_WRONG_VALUE_FOR_VAR, variableName, value);
        }

        return true;
    }

    public SessionVariable newSessionVariable() {
        return (SessionVariable) defaultSessionVariable.clone();
    }

    // Check if this sessionVariable can be set correctly
    public void checkUpdate(SystemVariable sessionVariable) throws DdlException {
        VarContext ctx = getVarContext(sessionVariable.getVariable());
        checkUpdate(sessionVariable, ctx.getFlag());
    }

    // Check if this setVar can be set correctly
    private void checkUpdate(SystemVariable setVar, int flag) throws DdlException {
        if ((flag & READ_ONLY) != 0) {
            ErrorReport.reportDdlException(ErrorCode.ERR_VARIABLE_IS_READONLY, setVar.getVariable());
        }
        if (setVar.getType() == SetType.GLOBAL && (flag & SESSION_ONLY) != 0) {
            ErrorReport.reportDdlException(ErrorCode.ERR_LOCAL_VARIABLE, setVar.getVariable());
        }
        if (setVar.getType() != SetType.GLOBAL && (flag & GLOBAL) != 0) {
            ErrorReport.reportDdlException(ErrorCode.ERR_GLOBAL_VARIABLE, setVar.getVariable());
        }
    }

    public void checkSystemVariableExist(SystemVariable setVar) throws DdlException {
        VarContext ctx = getVarContext(setVar.getVariable());
        if (ctx == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_UNKNOWN_SYSTEM_VARIABLE, setVar.getVariable(),
                    findSimilarVarNames(setVar.getVariable()));
        }
    }

    public boolean containsVariable(String name) {
        return ctxByVarName.containsKey(name);
    }

    // Entry of handling SetVarStmt
    // Input:
    //      sessionVariable: the variable of current session
    //      setVar: variable information that needs to be set
    public void setSystemVariable(SessionVariable sessionVariable, SystemVariable setVar, boolean onlySetSessionVar)
            throws DdlException {
        if (SessionVariable.DEPRECATED_VARIABLES.stream().anyMatch(c -> c.equalsIgnoreCase(setVar.getVariable()))) {
            return;
        }
        checkSystemVariableExist(setVar);

        if (setVar.getType() == SetType.VERBOSE) {
            ErrorReport.reportDdlException(ErrorCode.ERR_WRONG_TYPE_FOR_VAR, setVar.getVariable());
        }

        // Check variable attribute and setVar
        VarContext ctx = getVarContext(setVar.getVariable());
        checkUpdate(setVar, ctx.getFlag());

        // To modify to default value.
        VarAttr attr = ctx.getField().getAnnotation(VarAttr.class);
        String value;
        // If value is null, this is `set variable = DEFAULT`
        if (setVar.getResolvedExpression() != null) {
            value = setVar.getResolvedExpression().getStringValue();
        } else {
            value = ctx.getDefaultValue();
            if (value == null) {
                ErrorReport.reportDdlException(ErrorCode.ERR_NO_DEFAULT, attr.name());
            }
        }

        if (!onlySetSessionVar && setVar.getType() == SetType.GLOBAL) {
            setGlobalVariableAndWriteEditLog(ctx, attr.name(), value);
        }

        // set session variable
        setValue(sessionVariable, ctx.getField(), value);
    }

    private void setGlobalVariableAndWriteEditLog(VarContext ctx, String name, String value) throws DdlException {
        wLock.lock();
        try {
            setValue(ctx.getObj(), ctx.getField(), value);
            // write edit log
            GlobalVarPersistInfo info =
                    new GlobalVarPersistInfo(defaultSessionVariable, Lists.newArrayList(name));
            EditLog editLog = GlobalStateMgr.getCurrentState().getEditLog();
            editLog.logGlobalVariableV2(info);
        } finally {
            wLock.unlock();
        }
    }

    public void setCaseInsensitive(boolean caseInsensitive) throws DdlException {
        VarContext ctx = getVarContext(GlobalVariable.ENABLE_TABLE_NAME_CASE_INSENSITIVE);
        setGlobalVariableAndWriteEditLog(ctx, GlobalVariable.ENABLE_TABLE_NAME_CASE_INSENSITIVE, String.valueOf(caseInsensitive));
    }

    public void save(ImageWriter imageWriter) throws IOException, SRMetaBlockException {
        Map<String, String> m = new HashMap<>();
        Map<String, String> g = new HashMap<>();
        try {
            for (Field field : SessionVariable.class.getDeclaredFields()) {
                field.setAccessible(true);
                VarAttr attr = field.getAnnotation(VarAttr.class);
                if (attr == null || (attr.flag() & SESSION_ONLY) == SESSION_ONLY) {
                    continue;
                }
                switch (field.getType().getSimpleName()) {
                    case "boolean":
                    case "int":
                    case "long":
                    case "float":
                    case "double":
                    case "String":
                        m.put(attr.name(), field.get(defaultSessionVariable).toString());
                        break;
                    default:
                        // Unsupported type variable.
                        throw new IOException("invalid type: " + field.getType().getSimpleName());
                }
            }

            for (Field field : GlobalVariable.class.getDeclaredFields()) {
                field.setAccessible(true);
                VarAttr attr = field.getAnnotation(VarAttr.class);
                if (attr == null ||
                        ((attr.flag() & GLOBAL) != GLOBAL &&
                                !attr.name().equalsIgnoreCase(GlobalVariable.ENABLE_TABLE_NAME_CASE_INSENSITIVE))) {
                    continue;
                }

                switch (field.getType().getSimpleName()) {
                    case "boolean":
                    case "int":
                    case "long":
                    case "float":
                    case "double":
                    case "String":
                        g.put(attr.name(), field.get(null).toString());
                        break;
                    default:
                        // Unsupported type variable.
                        throw new IOException("invalid type: " + field.getType().getSimpleName());
                }
            }

        } catch (Exception e) {
            throw new IOException("failed to write session variable: " + e.getMessage());
        }

        SRMetaBlockWriter writer = imageWriter.getBlockWriter(SRMetaBlockID.VARIABLE_MGR, 1 + m.size() + 1 + g.size());

        writer.writeInt(m.size());
        for (Map.Entry<String, String> e : m.entrySet()) {
            writer.writeJson(new VariableInfo(e.getKey(), e.getValue()));
        }

        writer.writeInt(g.size());
        for (Map.Entry<String, String> e : g.entrySet()) {
            writer.writeJson(new VariableInfo(e.getKey(), e.getValue()));
        }
        writer.close();
    }

    public void load(SRMetaBlockReader reader) throws IOException, SRMetaBlockException, SRMetaBlockEOFException {
        reader.readCollection(VariableInfo.class, v -> {
            VarContext varContext = getVarContext(v.name);
            if (varContext != null) {
                try {
                    setValue(varContext.getObj(), varContext.getField(), v.variable);
                } catch (DdlException e) {
                    throw new IOException(e);
                }
            }
        });

        reader.readCollection(VariableInfo.class, v -> {
            VarContext varContext = getVarContext(v.name);
            if (varContext != null) {
                try {
                    setValue(varContext.getObj(), varContext.getField(), v.variable);
                } catch (DdlException e) {
                    throw new IOException(e);
                }
            }
        });
    }

    // this method is used to replace the `replayGlobalVariable()`
    public void replayGlobalVariableV2(GlobalVarPersistInfo info) throws DdlException {
        wLock.lock();
        try {
            String json = info.getPersistJsonString();
            JSONObject root = new JSONObject(json);
            for (String varName : root.keySet()) {
                VarContext varContext = getVarContext(varName);
                if (varContext == null) {
                    LOG.error("failed to get global variable {} when replaying", varName);
                    continue;
                }
                setValue(varContext.getObj(), varContext.getField(), root.get(varName).toString());
            }
        } finally {
            wLock.unlock();
        }
    }

    // Get variable value through variable name, used to satisfy statement like `SELECT @@comment_version`
    public void fillValue(SessionVariable var, VariableExpr desc) {
        VarContext ctx = getVarContext(desc.getName());
        if (ctx == null) {
            ErrorReport.reportSemanticException(ErrorCode.ERR_UNKNOWN_SYSTEM_VARIABLE, desc.getName(),
                    findSimilarVarNames(desc.getName()));
        }

        if (desc.getSetType() == SetType.GLOBAL) {
            rLock.lock();
            try {
                fillValue(ctx.getObj(), ctx.getField(), desc);
            } finally {
                rLock.unlock();
            }
        } else {
            fillValue(var, ctx.getField(), desc);
        }
    }

    private void fillValue(Object obj, Field field, VariableExpr desc) {
        try {
            switch (field.getType().getSimpleName()) {
                case "boolean":
                    desc.setType(Type.BOOLEAN);
                    desc.setValue(field.getBoolean(obj));
                    break;
                case "byte":
                    desc.setType(Type.TINYINT);
                    desc.setValue(field.getByte(obj));
                    break;
                case "short":
                    desc.setType(Type.SMALLINT);
                    desc.setValue(field.getShort(obj));
                    break;
                case "int":
                    desc.setType(Type.INT);
                    desc.setValue(field.getInt(obj));
                    break;
                case "long":
                    desc.setType(Type.BIGINT);
                    desc.setValue(field.getLong(obj));
                    break;
                case "float":
                    desc.setType(Type.FLOAT);
                    desc.setValue(field.getFloat(obj));
                    break;
                case "double":
                    desc.setType(Type.DOUBLE);
                    desc.setValue(field.getDouble(obj));
                    break;
                case "String":
                    desc.setType(Type.VARCHAR);
                    desc.setValue((String) field.get(obj));
                    break;
                default:
                    desc.setType(Type.VARCHAR);
                    desc.setValue("");
                    break;
            }
        } catch (IllegalAccessException e) {
            LOG.warn("Access failed.", e);
        }
    }

    // Get variable value through variable name, used to satisfy statement like `SELECT @@comment_version`
    public String getValue(SessionVariable var, VariableExpr desc) {
        VarContext ctx = getVarContext(desc.getName());
        if (ctx == null) {
            ErrorReport.reportSemanticException(ErrorCode.ERR_UNKNOWN_SYSTEM_VARIABLE, desc.getName(),
                    findSimilarVarNames(desc.getName()));
        }

        if (desc.getSetType() == SetType.GLOBAL) {
            rLock.lock();
            try {
                return getValue(ctx.getObj(), ctx.getField());
            } finally {
                rLock.unlock();
            }
        } else {
            return getValue(var, ctx.getField());
        }
    }

    private String getValue(Object obj, Field field) {
        try {
            switch (field.getType().getSimpleName()) {
                case "boolean":
                    return Boolean.toString(field.getBoolean(obj));
                case "byte":
                    return Byte.toString(field.getByte(obj));
                case "short":
                    return Short.toString(field.getShort(obj));
                case "int":
                    return Integer.toString(field.getInt(obj));
                case "long":
                    return Long.toString(field.getLong(obj));
                case "float":
                    return Float.toString(field.getFloat(obj));
                case "double":
                    return Double.toString(field.getDouble(obj));
                case "String":
                    return (String) field.get(obj);
                default:
                    return "";
            }
        } catch (IllegalAccessException e) {
            LOG.warn("Access failed.", e);
        }
        return "";
    }

    public String getDefaultValue(String variable) {
        VarContext ctx = getVarContext(variable);
        if (ctx == null) {
            ErrorReport.reportSemanticException(ErrorCode.ERR_UNKNOWN_SYSTEM_VARIABLE, variable,
                    findSimilarVarNames(variable));
        }

        String value = ctx.getDefaultValue();
        if (value == null) {
            ErrorReport.reportSemanticException(ErrorCode.ERR_NO_DEFAULT, variable);
        }
        return value;
    }

    // Dump all fields. Used for `show variables`, but note `sessionVar` would be null.
    public List<List<String>> dump(SetType type, SessionVariable sessionVar, PatternMatcher matcher) {
        List<List<String>> rows = Lists.newArrayList();
        // Hold the read lock when session dump, because this option need to access global variable.
        rLock.lock();
        try {
            for (Map.Entry<String, VarContext> entry : ctxByVarName.entrySet()) {
                // Filter variable not match to the regex.
                String name = StringUtils.isBlank(entry.getValue().getVarAttr().show()) ? entry.getKey()
                        : entry.getValue().getVarAttr().show();

                if (matcher != null && !matcher.match(name)) {
                    continue;
                }
                VarContext ctx = entry.getValue();

                // For session variables, the flag is VariableMgr.SESSION | VariableMgr.INVISIBLE
                // For global variables, the flag is VariableMgr.GLOBAL | VariableMgr.INVISIBLE
                if ((ctx.getFlag() > VariableMgr.INVISIBLE) && sessionVar != null &&
                        !sessionVar.isEnableShowAllVariables()) {
                    continue;
                }

                List<String> row = Lists.newArrayList();
                if (type != SetType.GLOBAL && ctx.getObj() == defaultSessionVariable) {
                    // In this condition, we may retrieve session variables for caller.
                    if (sessionVar != null) {
                        row.add(name);
                        String currentValue = getValue(sessionVar, ctx.getField());
                        row.add(currentValue);
                        if (type == SetType.VERBOSE) {
                            row.add(ctx.defaultValue);
                            row.add(ctx.defaultValue.equals(currentValue) ? "0" : "1");
                        }
                    } else {
                        LOG.error("sessionVar is null during dumping session variables.");
                        continue;
                    }
                } else {
                    row.add(name);
                    String currentValue = getValue(ctx.getObj(), ctx.getField());
                    row.add(currentValue);
                    if (type == SetType.VERBOSE) {
                        row.add(ctx.defaultValue);
                        row.add(ctx.defaultValue.equals(currentValue) ? "0" : "1");
                    }
                }

                if (row.get(0).equalsIgnoreCase(SessionVariable.SQL_MODE)) {
                    try {
                        row.set(1, SqlModeHelper.decode(Long.valueOf(row.get(1))));
                    } catch (DdlException e) {
                        row.set(1, "");
                        LOG.warn("Decode sql mode failed");
                    }
                }

                rows.add(row);
            }
        } finally {
            rLock.unlock();
        }

        // Sort all variables by variable name.
        rows.sort(Comparator.comparing(o -> o.get(0)));

        return rows;
    }

    public boolean shouldForwardToLeader(String name) {
        VarContext varContext = getVarContext(name);
        if (varContext == null) {
            // DEPRECATED_VARIABLES like enable_cbo don't have flag
            // we simply assume they all can forward to leader
            return true;
        } else {
            return (varContext.getFlag() & DISABLE_FORWARD_TO_LEADER) == 0;
        }
    }

    public Field getField(String name) {
        VarContext ctx = getVarContext(name);
        if (ctx == null) {
            return null;
        }
        return ctx.getField();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface VarAttr {
        // Name in show variables and set statement;
        String name();

        String alias() default "";

        // Use in show variables, must keep same with name or alias
        String show() default "";

        int flag() default 0;
    }

    private static class VarContext {
        private Field field;
        private Object obj;
        private int flag;
        private String defaultValue;
        private VarAttr varAttr;

        public VarContext(Field field, Object obj, int flag, String defaultValue, VarAttr varAttr) {
            this.field = field;
            this.obj = obj;
            this.flag = flag;
            this.defaultValue = defaultValue;
            this.varAttr = varAttr;
        }

        public Field getField() {
            return field;
        }

        public Object getObj() {
            return obj;
        }

        public int getFlag() {
            return flag;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public VarAttr getVarAttr() {
            return varAttr;
        }
    }

    private VarContext getVarContext(String name) {
        VarContext ctx = ctxByVarName.get(name);
        if (ctx == null) {
            ctx = ctxByVarName.get(aliases.get(name));
        }
        return ctx;
    }

    public String findSimilarVarNames(String inputName) {
        JaroWinklerDistance jaroWinklerDistance = new JaroWinklerDistance();
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        ctxByVarName.keySet().stream()
                .sorted(Comparator.comparingDouble(s ->
                        jaroWinklerDistance.apply(StringUtils.upperCase(s), StringUtils.upperCase(inputName))))
                .limit(3).map(e -> "'" + e + "'").forEach(joiner::add);
        return joiner.toString();
    }

    private static class VariableInfo {
        @SerializedName(value = "n")
        private String name;
        @SerializedName(value = "v")
        private String variable;

        public VariableInfo(String name, String variable) {
            this.name = name;
            this.variable = variable;
        }
    }
}
