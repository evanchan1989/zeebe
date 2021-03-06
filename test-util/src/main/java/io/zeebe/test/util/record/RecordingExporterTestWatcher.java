/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.zeebe.test.util.record;

import io.zeebe.util.ZbLogger;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;

public class RecordingExporterTestWatcher extends TestWatcher {

  public static final Logger LOG = new ZbLogger("io.zeebe.test.records");

  @Override
  protected void starting(Description description) {
    RecordingExporter.reset();
  }

  @Override
  protected void failed(Throwable e, Description description) {
    LOG.info("Test failed, following records where exported:");
    RecordingExporter.getRecords().forEach(r -> LOG.info(r.toJson()));
  }
}
