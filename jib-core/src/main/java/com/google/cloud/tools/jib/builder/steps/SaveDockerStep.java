/*
 * Copyright 2019 Google LLC.
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

import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.configuration.BuildConfiguration;
import com.google.cloud.tools.jib.docker.DockerClient;
import com.google.cloud.tools.jib.filesystem.FileOperations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Saves an image from the docker daemon. */
public class SaveDockerStep implements Callable<Path> {

  private final BuildConfiguration buildConfiguration;
  private final DockerClient dockerClient;

  SaveDockerStep(BuildConfiguration buildConfiguration, DockerClient dockerClient) {
    this.buildConfiguration = buildConfiguration;
    this.dockerClient = dockerClient;
  }

  @Override
  public Path call() throws IOException, InterruptedException {
    Path outputDir = Files.createTempDirectory("jib-docker-save");
    FileOperations.deleteRecursiveOnExit(outputDir);
    Path outputPath = outputDir.resolve("out.tar");
    ImageReference imageReference = buildConfiguration.getBaseImageConfiguration().getImage();
    dockerClient.save(imageReference, outputPath);
    return outputPath;
  }
}
