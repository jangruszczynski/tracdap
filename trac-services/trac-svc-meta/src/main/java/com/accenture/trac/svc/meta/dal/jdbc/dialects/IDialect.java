/*
 * Copyright 2020 Accenture Global Solutions Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.accenture.trac.svc.meta.dal.jdbc.dialects;

import com.accenture.trac.common.db.JdbcDialect;
import com.accenture.trac.svc.meta.dal.jdbc.JdbcErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;


public interface IDialect {

    JdbcDialect dialectCode();

    JdbcErrorCode mapErrorCode(SQLException e);

    void prepareMappingTable(Connection conn) throws SQLException;

    String mappingTableName();

    boolean supportsGeneratedKeys();

    int booleanType();
}
