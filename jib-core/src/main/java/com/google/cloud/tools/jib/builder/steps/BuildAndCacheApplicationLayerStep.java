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

import com.google.cloud.tools.jib.api.LayerConfiguration;
import com.google.cloud.tools.jib.api.LogEvent;
import com.google.cloud.tools.jib.blob.Blob;
import com.google.cloud.tools.jib.builder.ProgressEventDispatcher;
import com.google.cloud.tools.jib.builder.TimerEventDispatcher;
import com.google.cloud.tools.jib.cache.Cache;
import com.google.cloud.tools.jib.cache.CacheCorruptedException;
import com.google.cloud.tools.jib.cache.CachedLayer;
import com.google.cloud.tools.jib.configuration.BuildConfiguration;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.image.ReproducibleLayerBuilder;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/** Builds and caches application layers. */
class BuildAndCacheApplicationLayerStep implements Callable<PreparedLayer> {

  private static final String DESCRIPTION = "Building %s layer";

  /**
   * Makes a list of {@link BuildAndCacheApplicationLayerStep} for dependencies, resources, and
   * classes layers. Optionally adds an extra layer if configured to do so.
   */
  static ImmutableList<BuildAndCacheApplicationLayerStep> makeList(
      BuildConfiguration buildConfiguration,
      ProgressEventDispatcher.Factory progressEventDispatcherFactory) {
    List<LayerConfiguration> layerConfigurations = buildConfiguration.getLayerConfigurations();

    try (ProgressEventDispatcher progressEventDispatcher =
            progressEventDispatcherFactory.create(
                "preparing application layer builders", layerConfigurations.size());
        TimerEventDispatcher ignored =
            new TimerEventDispatcher(
                buildConfiguration.getEventHandlers(), "Preparing application layer builders")) {
      return layerConfigurations
          .stream()
          // Skips the layer if empty.
          .filter(layerConfiguration -> !layerConfiguration.getLayerEntries().isEmpty())
          .map(
              layerConfiguration ->
                  new BuildAndCacheApplicationLayerStep(
                      buildConfiguration,
                      progressEventDispatcher.newChildProducer(),
                      layerConfiguration.getName(),
                      layerConfiguration))
          .collect(ImmutableList.toImmutableList());
    }
  }

  private final BuildConfiguration buildConfiguration;
  private final ProgressEventDispatcher.Factory progressEventDispatcherFactory;

  private final String layerName;
  private final LayerConfiguration layerConfiguration;

  private BuildAndCacheApplicationLayerStep(
      BuildConfiguration buildConfiguration,
      ProgressEventDispatcher.Factory progressEventDispatcherFactory,
      String layerName,
      LayerConfiguration layerConfiguration) {
    this.buildConfiguration = buildConfiguration;
    this.progressEventDispatcherFactory = progressEventDispatcherFactory;
    this.layerName = layerName;
    this.layerConfiguration = layerConfiguration;
  }

  @Override
  public PreparedLayer call() throws IOException, CacheCorruptedException {
    String description = String.format(DESCRIPTION, layerName);

    EventHandlers eventHandlers = buildConfiguration.getEventHandlers();
    eventHandlers.dispatch(LogEvent.progress(description + "..."));

    try (ProgressEventDispatcher ignored =
            progressEventDispatcherFactory.create("building " + layerName + " layer", 1);
        TimerEventDispatcher ignored2 = new TimerEventDispatcher(eventHandlers, description)) {
      Cache cache = buildConfiguration.getApplicationLayersCache();

      // Don't build the layer if it exists already.
      Optional<CachedLayer> optionalCachedLayer =
          cache.retrieve(layerConfiguration.getLayerEntries());
      if (optionalCachedLayer.isPresent()) {
        return new PreparedLayer.Builder(optionalCachedLayer.get()).setName(layerName).build();
      }

      Blob layerBlob = new ReproducibleLayerBuilder(layerConfiguration.getLayerEntries()).build();
      CachedLayer cachedLayer =
          cache.writeUncompressedLayer(layerBlob, layerConfiguration.getLayerEntries());

      eventHandlers.dispatch(LogEvent.debug(description + " built " + cachedLayer.getDigest()));

      return new PreparedLayer.Builder(cachedLayer).setName(layerName).build();
    }
  }
}
