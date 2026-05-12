/*
 * Copyright (c) 2026. The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlesource.gerrit.plugins.reviewai;

import java.net.InetSocketAddress;
import java.net.Socket;
import org.h2.tools.Server;
import org.junit.rules.ExternalResource;

public class H2TcpServerRule extends ExternalResource {
  private static final int H2_TCP_PORT = 9092;

  private Server server;

  @Override
  protected void before() throws Throwable {
    if (isPortOpen()) {
      return;
    }
    server =
        Server.createTcpServer(
                "-tcpPort", Integer.toString(H2_TCP_PORT), "-tcpDaemon", "-ifNotExists")
            .start();
  }

  @Override
  protected void after() {
    if (server != null) {
      server.stop();
    }
  }

  private boolean isPortOpen() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("localhost", H2_TCP_PORT), 200);
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
