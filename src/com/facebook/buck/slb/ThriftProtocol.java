/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.slb;

import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.thrift.protocol.TProtocolFactory;

public enum ThriftProtocol {
  JSON(new TJSONProtocol.Factory()),
  BINARY(new TBinaryProtocol.Factory()),
  COMPACT(new TCompactProtocol.Factory());

  private TProtocolFactory factory;

  ThriftProtocol(TProtocolFactory factory) {
    this.factory = factory;
  }

  public TProtocolFactory getFactory() {
    return factory;
  }
}
