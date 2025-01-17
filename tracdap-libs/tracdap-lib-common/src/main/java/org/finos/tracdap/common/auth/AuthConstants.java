/*
 * Copyright 2022 Accenture Global Solutions Limited
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

package org.finos.tracdap.common.auth;

import org.finos.tracdap.common.auth.internal.UserInfo;

import io.grpc.Context;
import io.grpc.Metadata;


public class AuthConstants {

    public static final String TRAC_AUTH_TOKEN = "trac_auth_token";
    public static final String TRAC_USER_INFO = "user_info";

    public static final Metadata.Key<String> AUTH_TOKEN_METADATA_KEY =
            Metadata.Key.of(TRAC_AUTH_TOKEN, Metadata.ASCII_STRING_MARSHALLER);

    public static final Context.Key<String> AUTH_TOKEN_KEY =
            Context.key(TRAC_AUTH_TOKEN);

    public static final Context.Key<UserInfo> USER_INFO_KEY =
            Context.key(TRAC_USER_INFO);

}
