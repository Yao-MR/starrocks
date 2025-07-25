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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/analysis/MVColumnOneChildPatternTest.java

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

package com.starrocks.analysis;

import com.google.common.collect.Lists;
import com.starrocks.catalog.AggregateType;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.sql.analyzer.mvpattern.MVColumnOneChildPattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class MVColumnOneChildPatternTest {

    @Test
    public void testCorrectSum() {
        TableName tableName = new TableName("db", "table");
        SlotRef slotRef = new SlotRef(tableName, "c1");
        List<Expr> params = Lists.newArrayList();
        params.add(slotRef);
        FunctionCallExpr functionCallExpr = new FunctionCallExpr(AggregateType.SUM.name(), params);
        MVColumnOneChildPattern mvColumnOneChildPattern = new MVColumnOneChildPattern(
                AggregateType.SUM.name().toLowerCase());
        Assertions.assertTrue(mvColumnOneChildPattern.match(functionCallExpr));
    }

    @Test
    public void testCorrectMin() {
        TableName tableName = new TableName("db", "table");
        SlotRef slotRef = new SlotRef(tableName, "c1");
        List<Expr> child0Params = Lists.newArrayList();
        child0Params.add(slotRef);
        List<Expr> params = Lists.newArrayList();
        params.add(slotRef);
        FunctionCallExpr functionCallExpr = new FunctionCallExpr(AggregateType.MIN.name(), params);
        MVColumnOneChildPattern mvColumnOneChildPattern = new MVColumnOneChildPattern(
                AggregateType.MIN.name().toLowerCase());
        Assertions.assertTrue(mvColumnOneChildPattern.match(functionCallExpr));
    }

    @Test
    public void testCorrectCountField() {
        TableName tableName = new TableName("db", "table");
        SlotRef slotRef = new SlotRef(tableName, "c1");
        List<Expr> params = Lists.newArrayList();
        params.add(slotRef);
        FunctionCallExpr functionCallExpr = new FunctionCallExpr(FunctionSet.COUNT, params);
        MVColumnOneChildPattern mvColumnOneChildPattern = new MVColumnOneChildPattern(FunctionSet.COUNT.toLowerCase());
        Assertions.assertTrue(mvColumnOneChildPattern.match(functionCallExpr));
    }

    @Test
    public void testIncorrectLiteral() {
        IntLiteral intLiteral = new IntLiteral(1);
        List<Expr> params = Lists.newArrayList();
        params.add(intLiteral);
        FunctionCallExpr functionCallExpr = new FunctionCallExpr(AggregateType.SUM.name(), params);
        MVColumnOneChildPattern mvColumnOneChildPattern = new MVColumnOneChildPattern(
                AggregateType.SUM.name().toLowerCase());
        Assertions.assertTrue(mvColumnOneChildPattern.match(functionCallExpr));
    }

    @Test
    public void testIncorrectArithmeticExpr() {
        TableName tableName = new TableName("db", "table");
        SlotRef slotRef1 = new SlotRef(tableName, "c1");
        SlotRef slotRef2 = new SlotRef(tableName, "c2");
        ArithmeticExpr arithmeticExpr = new ArithmeticExpr(ArithmeticExpr.Operator.ADD, slotRef1, slotRef2);
        List<Expr> params = Lists.newArrayList();
        params.add(arithmeticExpr);
        FunctionCallExpr functionCallExpr = new FunctionCallExpr(AggregateType.SUM.name(), params);
        MVColumnOneChildPattern mvColumnOneChildPattern = new MVColumnOneChildPattern(
                AggregateType.SUM.name().toLowerCase());
        // Support complex expression now.
        Assertions.assertTrue(mvColumnOneChildPattern.match(functionCallExpr));
    }
}
