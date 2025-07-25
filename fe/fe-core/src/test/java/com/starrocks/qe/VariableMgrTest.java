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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/qe/VariableMgrTest.java

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

import com.google.common.collect.Lists;
import com.starrocks.analysis.IntLiteral;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.analysis.VariableExpr;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.ScalarType;
import com.starrocks.common.DdlException;
import com.starrocks.common.StarRocksException;
import com.starrocks.persist.EditLog;
import com.starrocks.persist.GlobalVarPersistInfo;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.ExpressionAnalyzer;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.analyzer.SetStmtAnalyzer;
import com.starrocks.sql.ast.SetStmt;
import com.starrocks.sql.ast.SetType;
import com.starrocks.sql.ast.SystemVariable;
import com.starrocks.utframe.UtFrameUtils;
import com.starrocks.utframe.UtFrameUtils.PseudoImage;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class VariableMgrTest {
    private static final Logger LOG = LoggerFactory.getLogger(VariableMgrTest.class);
    @Mocked
    private GlobalStateMgr globalStateMgr;
    @Mocked
    private EditLog editLog;

    private VariableMgr variableMgr = new VariableMgr();
    @BeforeEach
    public void setUp() {
        new Expectations() {
            {
                globalStateMgr.getEditLog();
                minTimes = 0;
                result = editLog;
            }
        };

        new Expectations(globalStateMgr) {
            {
                GlobalStateMgr.getCurrentState();
                minTimes = 0;
                result = globalStateMgr;

                globalStateMgr.getVariableMgr();
                minTimes = 0;
                result = variableMgr;
            }
        };
    }

    @Test
    public void testNormal() throws IllegalAccessException, NoSuchFieldException, StarRocksException {
        VariableMgr variableMgr = new VariableMgr();
        SessionVariable var = variableMgr.newSessionVariable();
        Assertions.assertEquals(2147483648L, var.getMaxExecMemByte());
        Assertions.assertEquals(300, var.getQueryTimeoutS());
        Assertions.assertEquals(false, var.isEnableProfile());
        Assertions.assertEquals(32L, var.getSqlMode());
        Assertions.assertEquals(true, var.isInnodbReadOnly());

        List<List<String>> rows = variableMgr.dump(SetType.SESSION, var, null);
        Assertions.assertTrue(rows.size() > 5);
        for (List<String> row : rows) {
            if (row.get(0).equalsIgnoreCase("exec_mem_limit")) {
                Assertions.assertEquals("2147483648", row.get(1));
            } else if (row.get(0).equalsIgnoreCase("enable_profile")) {
                Assertions.assertEquals("false", row.get(1));
            } else if (row.get(0).equalsIgnoreCase("query_timeout")) {
                Assertions.assertEquals("300", row.get(1));
            } else if (row.get(0).equalsIgnoreCase("sql_mode")) {
                Assertions.assertEquals("ONLY_FULL_GROUP_BY", row.get(1));
            }
        }

        // Set global variable
        SystemVariable setVar = new SystemVariable(SetType.GLOBAL, "exec_mem_limit", new IntLiteral(12999934L));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), null);
        variableMgr.setSystemVariable(var, setVar, false);
        Assertions.assertEquals(12999934L, var.getMaxExecMemByte());
        var = variableMgr.newSessionVariable();
        Assertions.assertEquals(12999934L, var.getMaxExecMemByte());

        SystemVariable setVar2 = new SystemVariable(SetType.GLOBAL, "parallel_fragment_exec_instance_num", new IntLiteral(5L));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar2)), null);
        variableMgr.setSystemVariable(var, setVar2, false);
        Assertions.assertEquals(5L, var.getParallelExecInstanceNum());
        var = variableMgr.newSessionVariable();
        Assertions.assertEquals(5L, var.getParallelExecInstanceNum());

        SystemVariable setVar3 = new SystemVariable(SetType.GLOBAL, "time_zone", new StringLiteral("Asia/Shanghai"));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar3)), null);
        variableMgr.setSystemVariable(var, setVar3, false);
        Assertions.assertEquals("Asia/Shanghai", var.getTimeZone());
        var = variableMgr.newSessionVariable();
        Assertions.assertEquals("Asia/Shanghai", var.getTimeZone());

        setVar3 = new SystemVariable(SetType.GLOBAL, "time_zone", new StringLiteral("CST"));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar3)), null);
        variableMgr.setSystemVariable(var, setVar3, false);
        Assertions.assertEquals("CST", var.getTimeZone());
        var = variableMgr.newSessionVariable();
        Assertions.assertEquals("CST", var.getTimeZone());

        // Set session variable
        setVar = new SystemVariable(SetType.GLOBAL, "exec_mem_limit", new IntLiteral(12999934L));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), null);
        variableMgr.setSystemVariable(var, setVar, false);
        Assertions.assertEquals(12999934L, var.getMaxExecMemByte());

        // onlySessionVar
        setVar = new SystemVariable(SetType.GLOBAL, "exec_mem_limit", new IntLiteral(12999935L));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), null);
        variableMgr.setSystemVariable(var, setVar, true);
        Assertions.assertEquals(12999935L, var.getMaxExecMemByte());

        setVar3 = new SystemVariable(SetType.SESSION, "time_zone", new StringLiteral("Asia/Jakarta"));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar3)), null);
        variableMgr.setSystemVariable(var, setVar3, false);
        Assertions.assertEquals("Asia/Jakarta", var.getTimeZone());

        // exec_mem_limit in expr style
        setVar = new SystemVariable(SetType.GLOBAL, "exec_mem_limit", new StringLiteral("20G"));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), null);
        variableMgr.setSystemVariable(var, setVar, true);
        Assertions.assertEquals(21474836480L, var.getMaxExecMemByte());
        setVar = new SystemVariable(SetType.GLOBAL, "exec_mem_limit", new StringLiteral("20m"));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), null);
        variableMgr.setSystemVariable(var, setVar, true);
        Assertions.assertEquals(20971520L, var.getMaxExecMemByte());

        // Get from name
        VariableExpr desc = new VariableExpr("exec_mem_limit");
        Assertions.assertEquals(var.getMaxExecMemByte() + "", variableMgr.getValue(var, desc));

        SystemVariable setVar4 = new SystemVariable(SetType.SESSION, "sql_mode", new StringLiteral(
                SqlModeHelper.encode("PIPES_AS_CONCAT").toString()));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar4)), null);
        variableMgr.setSystemVariable(var, setVar4, false);
        Assertions.assertEquals(2L, var.getSqlMode());

        // Test checkTimeZoneValidAndStandardize
        SystemVariable setVar5 = new SystemVariable(SetType.GLOBAL, "time_zone", new StringLiteral("+8:00"));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar5)), null);
        variableMgr.setSystemVariable(var, setVar5, false);
        Assertions.assertEquals("+08:00", variableMgr.newSessionVariable().getTimeZone());

        SystemVariable setVar6 = new SystemVariable(SetType.GLOBAL, "time_zone", new StringLiteral("8:00"));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar6)), null);
        variableMgr.setSystemVariable(var, setVar6, false);
        Assertions.assertEquals("+08:00", variableMgr.newSessionVariable().getTimeZone());

        SystemVariable setVar7 = new SystemVariable(SetType.GLOBAL, "time_zone", new StringLiteral("-8:00"));
        SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar7)), null);
        variableMgr.setSystemVariable(var, setVar7, false);
        Assertions.assertEquals("-08:00", variableMgr.newSessionVariable().getTimeZone());
    }

    @Test
    public void testInvalidType() {
        assertThrows(SemanticException.class, () -> {
            // Set global variable
            SystemVariable setVar = new SystemVariable(SetType.SESSION, "exec_mem_limit", new StringLiteral("abc"));
            try {
                SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), null);
            } catch (Exception e) {
                throw e;
            }
            Assertions.fail("No exception throws.");
        });
    }

    @Test
    public void testInvalidTimeZoneRegion() {
        assertThrows(SemanticException.class, () -> {
            // Set global variable
            // utc should be upper case (UTC)
            SystemVariable setVar = new SystemVariable(SetType.SESSION, "time_zone", new StringLiteral("utc"));
            try {
                SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), null);
            } catch (Exception e) {
                throw e;
            }
            Assertions.fail("No exception throws.");
        });
    }

    @Test
    public void testInvalidTimeZoneOffset() {
        assertThrows(SemanticException.class, () -> {
            // Set global variable
            SystemVariable setVar = new SystemVariable(SetType.SESSION, "time_zone", new StringLiteral("+15:00"));
            try {
                SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), null);
            } catch (Exception e) {
                throw e;
            }
            Assertions.fail("No exception throws.");
        });
    }

    @Test
    public void testInvalidExecMemLimit() {
        // Set global variable
        String[] values = {"2097151", "1k"};
        for (String value : values) {
            SystemVariable setVar = new SystemVariable(SetType.SESSION, "exec_mem_limit", new StringLiteral(value));
            try {
                SetStmtAnalyzer.analyze(new SetStmt(Lists.newArrayList(setVar)), null);
                Assertions.fail("No exception throws.");
            } catch (Exception e) {
                Assertions.assertEquals("Getting analyzing error. Detail message: exec_mem_limit must be equal " +
                        "or greater than 2097152.", e.getMessage());
            }
        }
    }

    @Test
    public void testReadOnly() {
        assertThrows(DdlException.class, () -> {
            VariableMgr variableMgr = new VariableMgr();
            VariableExpr desc = new VariableExpr("version_comment");
            LOG.info(variableMgr.getValue(null, desc));

            // Set global variable
            SystemVariable setVar = new SystemVariable(SetType.SESSION, "version_comment", null);
            variableMgr.setSystemVariable(null, setVar, false);
            Assertions.fail("No exception throws.");
        });
    }

    @Test
    public void testDumpInvisible() {
        VariableMgr variableMgr = new VariableMgr();
        SessionVariable sv = new SessionVariable();
        List<List<String>> vars = variableMgr.dump(SetType.SESSION, sv, null);
        Assertions.assertFalse(vars.toString().contains("enable_show_all_variables"));
        Assertions.assertFalse(vars.toString().contains("cbo_use_correlated_join_estimate"));

        sv.setEnableShowAllVariables(true);
        vars = variableMgr.dump(SetType.SESSION, sv, null);
        Assertions.assertTrue(vars.toString().contains("cbo_use_correlated_join_estimate"));

        vars = variableMgr.dump(SetType.SESSION, null, null);
        List<List<String>> vars1 = variableMgr.dump(SetType.GLOBAL, null, null);
        Assertions.assertTrue(vars.size() < vars1.size());

        List<List<String>> vars2 = variableMgr.dump(SetType.SESSION, null, null);
        Assertions.assertTrue(vars.size() == vars2.size());
    }

    @Test
    public void testWarehouseVar() {
        SystemVariable systemVariable =
                new SystemVariable(SetType.GLOBAL, SessionVariable.WAREHOUSE_NAME, new StringLiteral("warehouse_1"));
        VariableMgr variableMgr = new VariableMgr();
        try {
            variableMgr.setSystemVariable(null, systemVariable, false);
        } catch (DdlException e) {
            Assertions.assertEquals("Variable 'warehouse' is a SESSION variable and can't be used with SET GLOBAL",
                    e.getMessage());
        }
    }

    @Test
    public void testImagePersist() throws Exception {
        VariableMgr mgr = new VariableMgr();
        GlobalVarPersistInfo info = new GlobalVarPersistInfo();
        info.setPersistJsonString("{\"query_timeout\":100}");
        mgr.replayGlobalVariableV2(info);

        PseudoImage image = new PseudoImage();
        mgr.save(image.getImageWriter());

        VariableMgr mgr2 = new VariableMgr();
        mgr2.load(image.getMetaBlockReader());

        Assertions.assertEquals(100, mgr2.getDefaultSessionVariable().getQueryTimeoutS());
    }

    @Test
    public void testAutoCommit() throws Exception {
        VariableExpr desc = new VariableExpr("autocommit");
        ExpressionAnalyzer.analyzeExpressionIgnoreSlot(desc, UtFrameUtils.createDefaultCtx());

        Assertions.assertEquals("autocommit", desc.getName());
        Assertions.assertEquals(ScalarType.createType(PrimitiveType.BIGINT), desc.getType());
        Assertions.assertEquals((long) desc.getValue(), 1);
    }
}
