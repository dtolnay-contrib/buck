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

package com.facebook.buck.intellij.ideabuck.config;

import com.facebook.buck.intellij.ideabuck.ui.BuckEventsConsumer;
import com.facebook.buck.intellij.ideabuck.ui.BuckUIManager;
import com.facebook.buck.intellij.ideabuck.ws.BuckClientManager;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.BuckEventsHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckEventsConsumerFactory;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BuckModule implements Disposable {

  private Project mProject;
  private BuckEventsHandler mEventHandler;
  private BuckEventsConsumer mBuckEventsConsumer;
  private AtomicBoolean projectClosed;

  public BuckModule(Project project) {
    mProject = project;
    mEventHandler =
        new BuckEventsHandler(
            new BuckEventsConsumerFactory(mProject),
            new ExecuteOnBuckPluginConnect(),
            new ExecuteOnBuckPluginDisconnect());
  }

  public void connect() {
    BuckClientManager.getOrCreateClient(mProject, mEventHandler).connect();
  }

  public static BuckModule getInstance(Project project) {
    return project.getService(BuckModule.class);
  }

  public void projectOpened() {
    projectClosed = new AtomicBoolean(false);

    // connect to the Buck client
    connect();

    mBuckEventsConsumer = new BuckEventsConsumer(mProject);
    Disposer.register(DisposableService.getInstance(mProject), this);
  }

  @Override
  public void dispose() {
    projectClosed();
  }

  public void projectClosed() {
    if (projectClosed != null) {
      projectClosed.set(true);
    }
    BuckClientManager.getOrCreateClient(mProject, mEventHandler).disconnectWithoutRetry();
    if (mBuckEventsConsumer != null) {
      mBuckEventsConsumer.detach();
    }
  }

  public boolean isConnected() {
    return BuckClientManager.getOrCreateClient(mProject, mEventHandler).isConnected();
  }

  public void attachIfDetached() {
    attachIfDetached("");
  }

  public void attachIfDetached(String target) {
    if (!mBuckEventsConsumer.isAttached()) {
      attach(target);
    }
  }

  /**
   * A shortcut of calling attachWithText("Building " + target)
   *
   * @param target The target name to be built
   */
  public void attach(String target) {
    attachWithText("Building " + target);
  }

  /**
   * Detach and then re-attach the event consumers
   *
   * @param text The text to be displayed in the BuckTextNode
   */
  public void attachWithText(String text) {
    if (!isConnected()) {
      connect();
    }
    mBuckEventsConsumer.detach();

    mBuckEventsConsumer.attach(text);
  }

  public BuckEventsConsumer getBuckEventsConsumer() {
    return mBuckEventsConsumer;
  }

  private class ExecuteOnBuckPluginDisconnect implements Runnable {
    @Override
    public void run() {
      ApplicationManager.getApplication()
          .invokeLater(
              new Runnable() {
                @Override
                public void run() {
                  // If we haven't closed the project, then we show the message
                  if (!mProject.isDisposed()) {
                    BuckUIManager.getInstance(mProject)
                        .getBuckDebugPanel()
                        .outputConsoleMessage(
                            "Disconnected from buck!\n", ConsoleViewContentType.SYSTEM_OUTPUT);
                  }
                }
              });
      if (projectClosed != null && !projectClosed.get()) {
        // Tell the client that we got disconnected, but we can retry
        BuckClientManager.getOrCreateClient(mProject, mEventHandler).disconnectWithRetry();
      }
    }
  }

  private class ExecuteOnBuckPluginConnect implements Runnable {
    @Override
    public void run() {
      ApplicationManager.getApplication()
          .invokeLater(
              new Runnable() {
                @Override
                public void run() {
                  // If we connected to Buck and then closed the project, before getting
                  // the success message
                  if (!mProject.isDisposed()) {
                    BuckUIManager.getInstance(mProject)
                        .getBuckDebugPanel()
                        .outputConsoleMessage(
                            "Connected to buck!\n", ConsoleViewContentType.SYSTEM_OUTPUT);
                  }
                }
              });
    }
  }
}
