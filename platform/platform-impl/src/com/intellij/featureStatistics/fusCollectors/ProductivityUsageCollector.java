// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.featureStatistics.fusCollectors;

import com.intellij.featureStatistics.FeatureDescriptor;
import com.intellij.featureStatistics.ProductivityFeaturesRegistry;
import com.intellij.internal.statistic.beans.UsageDescriptor;
import com.intellij.internal.statistic.service.fus.collectors.ApplicationUsagesCollector;
import com.intellij.util.containers.HashSet;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public class ProductivityUsageCollector extends ApplicationUsagesCollector {

  private static String GROUP_ID = "statistics.productivity";
  @NotNull
  @Override
  public String getGroupId() {
    return GROUP_ID;
  }

  @NotNull
  @Override
  public Set<UsageDescriptor> getUsages() {
    Set<UsageDescriptor> usages = new HashSet<>();

    final ProductivityFeaturesRegistry registry = ProductivityFeaturesRegistry.getInstance();
    for (String featureId : registry.getFeatureIds()) {
      final FeatureDescriptor featureDescriptor = registry.getFeatureDescriptor(featureId);
      if (featureDescriptor != null) {
        int count = featureDescriptor.getUsageCount();
        if (count > 0) {
          usages.add(new UsageDescriptor(featureId, count));
        }
      }
    }

    return usages;
  }
}
