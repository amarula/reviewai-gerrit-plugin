/*
 * Copyright (c) 2025. The Android Open Source Project
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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.git.FileEntry;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.googlesource.gerrit.plugins.reviewai.utils.FileUtils.matchesExtensionList;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

@Slf4j
public class GitRepoFiles {
  public static final String REPO_PATTERN = "git/%s.git";

  private GitFileChunkBuilder gitFileChunkBuilder;
  private List<String> enabledFileExtensions;
  private long fileSize;

  public List<String> getGitRepoFilesAsJson(Configuration config, GerritChange change) {
    log.debug("Getting Repository files as JSON");
    gitFileChunkBuilder = new GitFileChunkBuilder(config);
    enabledFileExtensions = config.getEnabledFileExtensions();
    try (Repository repository = openRepository(change)) {
      List<Map<String, String>> chunkedFileContent = listFilesWithContent(repository);
      return chunkedFileContent.stream()
          .map(chunk -> getGson().toJson(chunk))
          .collect(Collectors.toList());
    } catch (IOException | GitAPIException e) {
      throw new RuntimeException("Failed to retrieve files in master branch: ", e);
    }
  }

  public List<FileEntry> getDirFiles(Configuration config, GerritChange change, String path) {
    log.debug("Getting files from selected directory");
    enabledFileExtensions = config.getEnabledFileExtensions();
    try (Repository repository = openRepository(change)) {
      Map<String, List<FileEntry>> dirFilesMap =
          getDirFilesMap(repository, PathFilter.create(path));
      log.debug("Retrieved file directories: {}", dirFilesMap.keySet());
      return dirFilesMap.get(path);
    } catch (IOException e) {
      throw new RuntimeException("Failed to retrieve files in path " + path, e);
    }
  }

  public String getFileContent(GerritChange change, String path) throws FileNotFoundException {
    try (Repository repository = openRepository(change);
        ObjectReader reader = repository.newObjectReader()) {
      RevTree tree = getMasterRevTree(repository);
      String content = readFileContent(reader, tree, path);
      if (content != null) {
        return content;
      } else {
        throw new FileNotFoundException("Error retrieving file at " + path);
      }
    } catch (IOException e) {
      throw new FileNotFoundException("File not found: " + path);
    }
  }

  private List<Map<String, String>> listFilesWithContent(Repository repository)
      throws IOException, GitAPIException {
    Map<String, List<FileEntry>> dirFilesMap = getDirFilesMap(repository, TreeFilter.ANY_DIFF);
    for (Map.Entry<String, List<FileEntry>> entry : dirFilesMap.entrySet()) {
      String dirPath = entry.getKey();
      log.debug("File from dirFilesMap processed: {}", dirPath);
      List<FileEntry> fileEntries = entry.getValue();
      gitFileChunkBuilder.addFiles(dirPath, fileEntries);
    }

    return gitFileChunkBuilder.getChunks();
  }

  private Map<String, List<FileEntry>> getDirFilesMap(Repository repository, TreeFilter filter)
      throws IOException {
    Map<String, List<FileEntry>> dirFilesMap = new LinkedHashMap<>();

    try (ObjectReader reader = repository.newObjectReader()) {
      RevTree tree = getMasterRevTree(repository);

      try (TreeWalk treeWalk = new TreeWalk(repository)) {
        treeWalk.addTree(tree);
        treeWalk.setRecursive(true);
        treeWalk.setFilter(filter);

        while (treeWalk.next()) {
          String path = treeWalk.getPathString();
          if (!matchesExtensionList(path, enabledFileExtensions)) continue;
          int lastSlashIndex = path.lastIndexOf('/');
          String dirPath = (lastSlashIndex != -1) ? path.substring(0, lastSlashIndex) : "";
          String content = getContent(reader, treeWalk);

          dirFilesMap
              .computeIfAbsent(dirPath, k -> new ArrayList<>())
              .add(new FileEntry(path, content, fileSize));
          log.debug("Repo File loaded: {}", path);
        }
      }
    }
    return dirFilesMap;
  }

  private Repository openRepository(GerritChange change) throws IOException {
    String repoPath = String.format(REPO_PATTERN, change.getProjectNameKey().toString());
    log.debug("Opening repository at path: {}", repoPath);
    FileRepositoryBuilder builder = new FileRepositoryBuilder();
    return builder
        .setGitDir(new File(repoPath))
        .readEnvironment()
        .findGitDir()
        .setMustExist(true)
        .build();
  }

  private RevTree getMasterRevTree(Repository repository) throws IOException {
    ObjectId lastCommitId = repository.resolve(Constants.R_HEADS + "master");
    try (RevWalk revWalk = new RevWalk(repository)) {
      return revWalk.parseCommit(lastCommitId).getTree();
    } catch (NullPointerException e) {
      log.warn("Error retrieving Master Rev Tree for ID `{}`", lastCommitId, e);
      throw new IOException(e);
    }
  }

  private String readFileContent(ObjectReader reader, RevTree tree, String path)
      throws IOException {
    try (TreeWalk treeWalk = TreeWalk.forPath(reader, path, tree)) {
      if (treeWalk != null) {
        return getContent(reader, treeWalk);
      }
      return null;
    }
  }

  private String getContent(ObjectReader reader, TreeWalk treeWalk) throws IOException {
    ObjectId objectId = treeWalk.getObjectId(0);
    byte[] bytes = reader.open(objectId).getBytes();
    fileSize = bytes.length;

    return new String(bytes, StandardCharsets.UTF_8);
  }
}
