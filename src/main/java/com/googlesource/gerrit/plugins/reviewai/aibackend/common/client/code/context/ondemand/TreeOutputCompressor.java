/*
 * Copyright (c) 2026. Amarula Solutions
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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

class TreeOutputCompressor {
  static final int DEFAULT_MAX_LENGTH = 1_000;

  private static final String ELLIPSIS = "...";

  private final int maxLength;

  TreeOutputCompressor() {
    this(DEFAULT_MAX_LENGTH);
  }

  TreeOutputCompressor(int maxLength) {
    this.maxLength = maxLength;
  }

  String format(List<String> paths, String subdir) {
    List<String> providedPaths = paths.stream().filter(Objects::nonNull).collect(Collectors.toList());
    String fullOutput = String.join("\n", providedPaths);
    if (fullOutput.length() <= maxLength) {
      return fullOutput;
    }

    List<String> normalizedPaths =
        providedPaths.stream()
            .filter(path -> !path.isBlank())
            .distinct()
            .sorted()
            .collect(Collectors.toList());

    TreeNode root = buildTree(normalizedPaths);
    TreeNode startNode = root.find(normalizePath(subdir));
    return compressChildren(startNode == null ? root.children() : startNode.children());
  }

  private String compressChildren(Collection<TreeNode> children) {
    StringBuilder output = new StringBuilder();
    for (TreeNode child : children) {
      String expanded = child.expandedOutput();
      if (appendBlockIfFits(output, expanded)) {
        continue;
      }

      String collapsed = child.isDirectory() ? child.path() + "/" + ELLIPSIS : ELLIPSIS;
      if (!appendLineIfFits(output, collapsed)) {
        appendLineIfFits(output, ELLIPSIS);
        break;
      }
    }
    return output.toString();
  }

  private boolean appendBlockIfFits(StringBuilder output, String block) {
    int separatorLength = output.isEmpty() || block.isEmpty() ? 0 : 1;
    if (output.length() + separatorLength + block.length() > maxLength) {
      return false;
    }
    if (separatorLength == 1) {
      output.append('\n');
    }
    output.append(block);
    return true;
  }

  private boolean appendLineIfFits(StringBuilder output, String line) {
    int separatorLength = output.isEmpty() ? 0 : 1;
    if (output.length() + separatorLength + line.length() > maxLength) {
      return false;
    }
    if (separatorLength == 1) {
      output.append('\n');
    }
    output.append(line);
    return true;
  }

  private static TreeNode buildTree(List<String> paths) {
    TreeNode root = new TreeNode("");
    for (String path : paths) {
      root.add(path);
    }
    return root;
  }

  private static String normalizePath(String path) {
    if (path == null) {
      return "";
    }
    return path.replaceAll("^/+", "").replaceAll("/+$", "");
  }

  private static class TreeNode {
    private final String path;
    private final Map<String, TreeNode> children = new TreeMap<>();
    private boolean file;

    private TreeNode(String path) {
      this.path = path;
    }

    private void add(String filePath) {
      String[] parts = filePath.split("/");
      TreeNode current = this;
      for (int index = 0; index < parts.length; index++) {
        String childName = parts[index];
        String childPath = current.path.isEmpty() ? childName : current.path + "/" + childName;
        current = current.children.computeIfAbsent(childName, name -> new TreeNode(childPath));
        if (index == parts.length - 1) {
          current.file = true;
        }
      }
    }

    private TreeNode find(String requestedPath) {
      if (requestedPath.isEmpty()) {
        return this;
      }
      String[] parts = requestedPath.split("/");
      TreeNode current = this;
      for (String part : parts) {
        current = current.children.get(part);
        if (current == null) {
          return null;
        }
      }
      return current;
    }

    private Collection<TreeNode> children() {
      return children.values();
    }

    private String path() {
      return path;
    }

    private boolean isDirectory() {
      return !children.isEmpty();
    }

    private String expandedOutput() {
      List<String> expandedPaths = new ArrayList<>();
      collectExpandedPaths(expandedPaths);
      return String.join("\n", expandedPaths);
    }

    private void collectExpandedPaths(List<String> expandedPaths) {
      if (file) {
        expandedPaths.add(path);
      }
      children.values().forEach(child -> child.collectExpandedPaths(expandedPaths));
    }
  }
}
