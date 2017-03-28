/*
 * Copyright 2017 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.extra.transforms;

import com.google.common.collect.Maps;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

/**
 * A {@link DoFn} that manages an external resource.
 */
public abstract class DoFnWithResource<InputT, OutputT, ResourceT> extends DoFn<InputT, OutputT> {

  private static final Logger LOG = LoggerFactory.getLogger(DoFnWithResource.class);
  private static final ConcurrentMap<String, Object> resources = Maps.newConcurrentMap();

  private final String instanceId;
  private String resourceId = null;

  /**
   * Resource type for sharing the resource among {@link DoFn} instances.
   */
  public enum ResourceType {
    /**
     * One instance of the resource per sub-class.
     */
    PER_CLASS,

    /**
     * One instance of the resource per sub-class instance.
     */
    PER_INSTANCE,

    /**
     * One instance of the resource per worker CPU core.
     */
    PER_CORE
  }

  /**
   * Get resource type.
   */
  public abstract ResourceType getResourceType();

  /**
   * Create resource.
   */
  public abstract ResourceT createResource();

  protected DoFnWithResource() {
    this.instanceId = this.getClass().getName() + "-" + UUID.randomUUID().toString();
  }

  @Setup
  public void setup() {
    switch (getResourceType()) {
      case PER_CLASS:
        resourceId = this.getClass().getName();
        break;
      case PER_INSTANCE:
        resourceId = instanceId;
        break;
      case PER_CORE:
        resourceId = instanceId + "-" + this.toString();
        break;
    }
    resources.computeIfAbsent(resourceId, key -> {
      LOG.info("Creating resource {}", resourceId);
      return createResource();
    });
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    super.populateDisplayData(builder);
    builder.add(DisplayData.item("Resource Type", getResourceType().toString()));
  }

  @SuppressWarnings("unchecked")
  public ResourceT getResource() {
    return (ResourceT) resources.get(resourceId);
  }

}
