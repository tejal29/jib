/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.builder.steps;

import com.google.cloud.tools.jib.api.DescriptorDigest;
import com.google.cloud.tools.jib.api.LogEvent;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.blob.Blob;
import com.google.cloud.tools.jib.blob.BlobDescriptor;
import com.google.cloud.tools.jib.builder.ProgressEventDispatcher;
import com.google.cloud.tools.jib.builder.TimerEventDispatcher;
import com.google.cloud.tools.jib.configuration.BuildConfiguration;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.event.progress.ThrottledAccumulatingConsumer;
import com.google.cloud.tools.jib.http.Authorization;
import com.google.cloud.tools.jib.registry.RegistryClient;
import java.io.IOException;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;

/** Pushes a BLOB to the target registry. */
class PushBlobStep implements Callable<BlobDescriptor> {

  private static final String DESCRIPTION = "Pushing BLOB ";

  private final BuildConfiguration buildConfiguration;
  private final ProgressEventDispatcher.Factory progressEventDispatcherFactory;

  @Nullable private final Authorization authorization;
  private final BlobDescriptor blobDescriptor;
  private final Blob blob;
  private final boolean forcePush;

  PushBlobStep(
      BuildConfiguration buildConfiguration,
      ProgressEventDispatcher.Factory progressEventDispatcherFactory,
      @Nullable Authorization authorization,
      BlobDescriptor blobDescriptor,
      Blob blob,
      boolean forcePush) {
    this.buildConfiguration = buildConfiguration;
    this.progressEventDispatcherFactory = progressEventDispatcherFactory;
    this.authorization = authorization;
    this.blobDescriptor = blobDescriptor;
    this.blob = blob;
    this.forcePush = forcePush;
  }

  @Override
  public BlobDescriptor call() throws IOException, RegistryException {
    EventHandlers eventHandlers = buildConfiguration.getEventHandlers();
    DescriptorDigest blobDigest = blobDescriptor.getDigest();
    try (ProgressEventDispatcher progressEventDispatcher =
            progressEventDispatcherFactory.create(
                "pushing blob " + blobDigest, blobDescriptor.getSize());
        TimerEventDispatcher ignored =
            new TimerEventDispatcher(eventHandlers, DESCRIPTION + blobDescriptor);
        ThrottledAccumulatingConsumer throttledProgressReporter =
            new ThrottledAccumulatingConsumer(progressEventDispatcher::dispatchProgress)) {
      RegistryClient registryClient =
          buildConfiguration
              .newTargetImageRegistryClientFactory()
              .setAuthorization(authorization)
              .newRegistryClient();

      // check if the BLOB is available
      if (!forcePush && registryClient.checkBlob(blobDigest).isPresent()) {
        eventHandlers.dispatch(
            LogEvent.info("BLOB : " + blobDescriptor + " already exists on registry"));
        return blobDescriptor;
      }

      // If base and target images are in the same registry, then use mount/from to try mounting the
      // BLOB from the base image repository to the target image repository and possibly avoid
      // having to push the BLOB. See
      // https://docs.docker.com/registry/spec/api/#cross-repository-blob-mount for details.
      String baseRegistry = buildConfiguration.getBaseImageConfiguration().getImageRegistry();
      String baseRepository = buildConfiguration.getBaseImageConfiguration().getImageRepository();
      String targetRegistry = buildConfiguration.getTargetImageConfiguration().getImageRegistry();
      String sourceRepository = targetRegistry.equals(baseRegistry) ? baseRepository : null;
      registryClient.pushBlob(blobDigest, blob, sourceRepository, throttledProgressReporter);

      return blobDescriptor;
    }
  }
}
