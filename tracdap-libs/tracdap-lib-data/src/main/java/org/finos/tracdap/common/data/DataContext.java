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

package org.finos.tracdap.common.data;

import org.finos.tracdap.common.concurrent.ExecutionContext;
import io.netty.util.concurrent.OrderedEventExecutor;
import org.apache.arrow.memory.BufferAllocator;

public class DataContext extends ExecutionContext implements IDataContext {

    private final BufferAllocator arrowAllocator;

    public DataContext(OrderedEventExecutor eventLoop, BufferAllocator arrowAllocator) {
        super(eventLoop);
        this.arrowAllocator = arrowAllocator;
    }

    @Override
    public BufferAllocator arrowAllocator() {
        return arrowAllocator;
    }
}