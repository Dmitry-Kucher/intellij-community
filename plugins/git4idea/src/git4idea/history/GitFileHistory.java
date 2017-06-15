/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package git4idea.history;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Couple;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.concurrency.Semaphore;
import git4idea.GitFileRevision;
import git4idea.GitRevisionNumber;
import git4idea.GitVcs;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitLineHandlerAdapter;
import git4idea.commands.GitSimpleHandler;
import git4idea.config.GitVersion;
import git4idea.config.GitVersionSpecialty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.intellij.util.ObjectUtils.notNull;
import static git4idea.history.GitLogParser.GitLogOption.*;

/**
 * An implementation of file history algorithm with renames detection.
 * <p>
 * 'git log --follow' does detect renames, but it has a bug - merge commits aren't handled properly: they just disappear from the history.
 * See http://kerneltrap.org/mailarchive/git/2009/1/30/4861054 and the whole thread about that: --follow is buggy, but maybe it won't be fixed.
 * To get the whole history through renames we do the following:
 * 1. 'git log <file>' - and we get the history since the first rename, if there was one.
 * 2. 'git show -M --follow --name-status <first_commit_id> -- <file>'
 * where <first_commit_id> is the hash of the first commit in the history we got in #1.
 * With this command we get the rename-detection-friendly information about the first commit of the given file history.
 * (by specifying the <file> we filter out other changes in that commit; but in that case rename detection requires '--follow' to work,
 * that's safe for one commit though)
 * If the first commit was ADDING the file, then there were no renames with this file, we have the full history.
 * But if the first commit was RENAMING the file, we are going to query for the history before rename.
 * Now we have the previous name of the file:
 * <p>
 * ~/sandbox/git # git show --oneline --name-status -M 4185b97
 * 4185b97 renamed a to b
 * R100    a       b
 * <p>
 * 3. 'git log <rename_commit_id> -- <previous_file_name>' - get the history of a before the given commit.
 * We need to specify <rename_commit_id> here, because <previous_file_name> could have some new history, which has nothing common with our <file>.
 * Then we repeat 2 and 3 until the first commit is ADDING the file, not RENAMING it.
 * <p>
 * TODO: handle multiple repositories configuration: a file can be moved from one repo to another
 */
public class GitFileHistory {
  private static final Logger LOG = Logger.getInstance("#git4idea.history.GitFileHistory");

  @NotNull private final Project myProject;
  @NotNull private final VirtualFile myRoot;
  @NotNull private final FilePath myPath;
  @NotNull private final VcsRevisionNumber myStartingRevision;

  public GitFileHistory(@NotNull Project project, @NotNull VirtualFile root, @NotNull FilePath path, @NotNull VcsRevisionNumber revision) {
    myProject = project;
    myRoot = root;
    myPath = GitHistoryUtils.getLastCommitName(myProject, path);
    myStartingRevision = revision;
  }

  public void history(@NotNull Consumer<GitFileRevision> consumer,
                      @NotNull Consumer<VcsException> exceptionConsumer,
                      String... parameters) {
    GitVcs vcs = GitVcs.getInstance(myProject);
    GitVersion version = vcs != null ? vcs.getVersion() : GitVersion.NULL;

    GitLogParser logParser = new GitLogParser(myProject, GitLogParser.NameStatus.STATUS,
                                              HASH, COMMIT_TIME, AUTHOR_NAME, AUTHOR_EMAIL, COMMITTER_NAME, COMMITTER_EMAIL, PARENTS,
                                              SUBJECT, BODY, RAW_BODY, AUTHOR_TIME);
    GitLogRecordConsumer recordConsumer = new GitLogRecordConsumer(version, consumer, exceptionConsumer);

    while (recordConsumer.getCurrentPath() != null && recordConsumer.getFirstCommitParent() != null) {
      GitLineHandler handler = createLogHandler(myProject, version, myRoot, logParser,
                                                recordConsumer.getCurrentPath(), recordConsumer.getFirstCommitParent(), parameters);
      MyGitLineHandlerAdapter lineListener = new MyGitLineHandlerAdapter(handler, logParser, recordConsumer, exceptionConsumer);
      lineListener.runAndWait();
      if (lineListener.hasCriticalFailure()) {
        return;
      }

      try {
        recordConsumer.updateFirstCommitParentAndPath();
      }
      catch (VcsException e) {
        LOG.warn("Tried to get first commit rename path", e);
        exceptionConsumer.consume(e);
        return;
      }
    }
  }

  @NotNull
  private static GitLineHandler createLogHandler(@NotNull Project project,
                                                 @NotNull GitVersion version,
                                                 @NotNull VirtualFile root,
                                                 @NotNull GitLogParser parser,
                                                 @NotNull FilePath path,
                                                 @NotNull String lastCommit,
                                                 String... parameters) {
    final GitLineHandler h = new GitLineHandler(project, root, GitCommand.LOG);
    h.setStdoutSuppressed(true);
    h.addParameters("--name-status", parser.getPretty(), "--encoding=UTF-8", lastCommit);
    if (GitVersionSpecialty.FULL_HISTORY_SIMPLIFY_MERGES_WORKS_CORRECTLY.existsIn(version) && Registry.is("git.file.history.full")) {
      h.addParameters("--full-history", "--simplify-merges");
    }
    if (parameters != null && parameters.length > 0) {
      h.addParameters(parameters);
    }
    h.endOptions();
    h.addRelativePaths(path);
    return h;
  }

  @NotNull
  private static VirtualFile getRoot(@NotNull Project project, @NotNull FilePath path) throws VcsException {
    VirtualFile root = ProjectLevelVcsManager.getInstance(project).getVcsRootFor(path);
    if (root == null) {
      throw new VcsException("The file " + path + " is not under vcs.");
    }
    return root;
  }

  /**
   * Retrieves the history of the file, including renames.
   *
   * @param project
   * @param path              FilePath which history is queried.
   * @param root              Git root - optional: if this is null, then git root will be detected automatically.
   * @param consumer          This consumer is notified ({@link Consumer#consume(Object)} when new history records are retrieved.
   * @param exceptionConsumer This consumer is notified in case of error while executing git command.
   * @param parameters        Optional parameters which will be added to the git log command just before the path.
   */
  public static void history(@NotNull Project project,
                             @NotNull FilePath path,
                             @Nullable VirtualFile root,
                             @Nullable VcsRevisionNumber startingFrom,
                             @NotNull Consumer<GitFileRevision> consumer,
                             @NotNull Consumer<VcsException> exceptionConsumer,
                             String... parameters) {
    try {
      VirtualFile repositoryRoot = root == null ? getRoot(project, path) : root;
      VcsRevisionNumber revision = startingFrom == null ? GitRevisionNumber.HEAD : startingFrom;
      new GitFileHistory(project, repositoryRoot, path, revision).history(consumer, exceptionConsumer, parameters);
    }
    catch (VcsException e) {
      exceptionConsumer.consume(e);
    }
  }

  @NotNull
  public static List<VcsFileRevision> history(@NotNull Project project,
                                              @NotNull FilePath path,
                                              @Nullable VirtualFile root,
                                              @NotNull VcsRevisionNumber startingFrom,
                                              String... parameters) throws VcsException {
    List<VcsFileRevision> revisions = new ArrayList<>();
    List<VcsException> exceptions = new ArrayList<>();

    history(project, path, root, startingFrom, revisions::add, exceptions::add, parameters);

    if (!exceptions.isEmpty()) {
      throw exceptions.get(0);
    }
    return revisions;
  }

  @NotNull
  public static List<VcsFileRevision> history(@NotNull Project project,
                                              @NotNull FilePath path,
                                              @Nullable VirtualFile root,
                                              String... parameters) throws VcsException {
    return history(project, path, root, GitRevisionNumber.HEAD, parameters);
  }

  /**
   * Get history for the file
   *
   * @param project the context project
   * @param path    the file path
   * @return the list of the revisions
   * @throws VcsException if there is problem with running git
   */
  @NotNull
  public static List<VcsFileRevision> history(@NotNull Project project, @NotNull FilePath path, String... parameters) throws VcsException {
    return history(project, path, getRoot(project, path), parameters);
  }

  private static class MyTokenAccumulator {
    @NotNull private final StringBuilder myBuffer = new StringBuilder();
    @NotNull private final GitLogParser myParser;

    private boolean myNotStarted = true;

    public MyTokenAccumulator(@NotNull GitLogParser parser) {
      myParser = parser;
    }

    @Nullable
    public GitLogRecord acceptLine(String s) {
      final boolean recordStart = s.startsWith(GitLogParser.RECORD_START);
      if (recordStart) {
        s = s.substring(GitLogParser.RECORD_START.length());
      }

      if (myNotStarted) {
        myBuffer.append(s);
        myBuffer.append("\n");

        myNotStarted = false;
        return null;
      }
      else if (recordStart) {
        final String line = myBuffer.toString();
        myBuffer.setLength(0);

        myBuffer.append(s);
        myBuffer.append("\n");

        return processResult(line);
      }
      else {
        myBuffer.append(s);
        myBuffer.append("\n");
        return null;
      }
    }

    @Nullable
    public GitLogRecord processLast() {
      return processResult(myBuffer.toString());
    }

    @Nullable
    private GitLogRecord processResult(@NotNull String line) {
      return myParser.parseOneRecord(line);
    }
  }

  private static class MyGitLineHandlerAdapter extends GitLineHandlerAdapter {
    @NotNull private final AtomicBoolean myCriticalFailure = new AtomicBoolean();
    @NotNull private final Semaphore mySemaphore = new Semaphore();
    @NotNull private final GitLineHandler myHandler;
    @NotNull private final MyTokenAccumulator myAccumulator;
    @NotNull private final Consumer<GitLogRecord> myRecordConsumer;
    @NotNull private final Consumer<VcsException> myExceptionConsumer;

    public MyGitLineHandlerAdapter(@NotNull GitLineHandler handler,
                                   @NotNull GitLogParser logParser,
                                   @NotNull Consumer<GitLogRecord> recordConsumer,
                                   @NotNull Consumer<VcsException> exceptionConsumer) {
      myHandler = handler;
      myRecordConsumer = recordConsumer;
      myExceptionConsumer = exceptionConsumer;
      myAccumulator = new MyTokenAccumulator(logParser);

      myHandler.addLineListener(this);
    }

    public void runAndWait() {
      mySemaphore.down();
      myHandler.start();
      mySemaphore.waitFor();
    }

    @Override
    public void onLineAvailable(String line, Key outputType) {
      final GitLogRecord record = myAccumulator.acceptLine(line);
      if (record != null) {
        record.setUsedHandler(myHandler);
        myRecordConsumer.consume(record);
      }
    }

    @Override
    public void startFailed(Throwable exception) {
      //noinspection ThrowableInstanceNeverThrown
      try {
        myExceptionConsumer.consume(new VcsException(exception));
      }
      finally {
        myCriticalFailure.set(true);
        mySemaphore.up();
      }
    }

    @Override
    public void processTerminated(int exitCode) {
      try {
        super.processTerminated(exitCode);
        final GitLogRecord record = myAccumulator.processLast();
        if (record != null) {
          record.setUsedHandler(myHandler);
          myRecordConsumer.consume(record);
        }
      }
      catch (ProcessCanceledException ignored) {
      }
      catch (Throwable t) {
        LOG.error(t);
        myExceptionConsumer.consume(new VcsException("Internal error " + t.getMessage(), t));
        myCriticalFailure.set(true);
      }
      finally {
        mySemaphore.up();
      }
    }

    public boolean hasCriticalFailure() {
      return myCriticalFailure.get();
    }
  }

  private class GitLogRecordConsumer implements Consumer<GitLogRecord> {
    @NotNull private final AtomicBoolean mySkipFurtherOutput = new AtomicBoolean();
    @NotNull private final AtomicReference<String> myFirstCommit = new AtomicReference<>(myStartingRevision.asString());
    @NotNull private final AtomicReference<String> myFirstCommitParent = new AtomicReference<>(myStartingRevision.asString());
    @NotNull private final AtomicReference<FilePath> myCurrentPath = new AtomicReference<>(myPath);
    @NotNull private final GitVersion myVersion;
    @NotNull private final Consumer<VcsException> myExceptionConsumer;
    @NotNull private final Consumer<GitFileRevision> myRevisionConsumer;

    public GitLogRecordConsumer(@NotNull GitVersion version,
                                @NotNull Consumer<GitFileRevision> revisionConsumer,
                                @NotNull Consumer<VcsException> exceptionConsumer) {
      myVersion = version;
      myExceptionConsumer = exceptionConsumer;
      myRevisionConsumer = revisionConsumer;
    }

    @Override
    public void consume(@NotNull GitLogRecord record) {
      if (mySkipFurtherOutput.get()) {
        return;
      }

      final GitRevisionNumber revision = new GitRevisionNumber(record.getHash(), record.getDate());
      myFirstCommit.set(record.getHash());
      final String[] parentHashes = record.getParentsHashes();
      if (parentHashes.length < 1) {
        myFirstCommitParent.set(null);
      }
      else {
        myFirstCommitParent.set(parentHashes[0]);
      }
      final String message = record.getFullMessage();

      FilePath revisionPath;
      try {
        final List<FilePath> paths = record.getFilePaths(myRoot);
        if (paths.size() > 0) {
          revisionPath = paths.get(0);
        }
        else {
          // no paths are shown for merge commits, so we're using the saved path we're inspecting now
          revisionPath = myCurrentPath.get();
        }

        Couple<String> authorPair = Couple.of(record.getAuthorName(), record.getAuthorEmail());
        Couple<String> committerPair = Couple.of(record.getCommitterName(), record.getCommitterEmail());
        Collection<String> parents = Arrays.asList(parentHashes);
        myRevisionConsumer
          .consume(new GitFileRevision(myProject, myRoot, revisionPath, revision, Couple.of(authorPair, committerPair), message,
                                       null, new Date(record.getAuthorTimeStamp()), parents));
        List<GitLogStatusInfo> statusInfos = record.getStatusInfos();
        if (statusInfos.isEmpty()) {
          // can safely be empty, for example, for simple merge commits that don't change anything.
          return;
        }
        if (statusInfos.get(0).getType() == GitChangeType.ADDED && !myPath.isDirectory()) {
          mySkipFurtherOutput.set(true);
        }
      }
      catch (VcsException e) {
        myExceptionConsumer.consume(e);
      }
    }

    @Nullable
    public String getFirstCommitParent() {
      return myFirstCommitParent.get();
    }

    @Nullable
    public FilePath getCurrentPath() {
      return myCurrentPath.get();
    }

    public void updateFirstCommitParentAndPath() throws VcsException {
      Pair<String, FilePath> firstCommitParentAndPath = getFirstCommitParentAndPathIfRename(myFirstCommit.get(), myCurrentPath.get());
      if (firstCommitParentAndPath == null) {
        myCurrentPath.set(null);
        myFirstCommitParent.set(null);
      }
      else {
        myCurrentPath.set(firstCommitParentAndPath.second);
        myFirstCommitParent.set(firstCommitParentAndPath.first);
      }
      mySkipFurtherOutput.set(false);
    }

    /**
     * Gets info of the given commit and checks if it was a RENAME.
     * If yes, returns the older file path, which file was renamed from.
     * If it's not a rename, returns null.
     */
    @Nullable
    private Pair<String, FilePath> getFirstCommitParentAndPathIfRename(@NotNull String commit,
                                                                       @NotNull FilePath filePath) throws VcsException {
      // 'git show -M --name-status <commit hash>' returns the information about commit and detects renames.
      // NB: we can't specify the filepath, because then rename detection will work only with the '--follow' option, which we don't wanna use.
      GitSimpleHandler h = new GitSimpleHandler(myProject, myRoot, GitCommand.SHOW);
      GitLogParser parser = new GitLogParser(myProject, GitLogParser.NameStatus.STATUS, HASH, COMMIT_TIME, PARENTS);
      h.setStdoutSuppressed(true);
      h.addParameters("-M", "--name-status", parser.getPretty(), "--encoding=UTF-8", commit);
      if (!GitVersionSpecialty.FOLLOW_IS_BUGGY_IN_THE_LOG.existsIn(myVersion)) {
        h.addParameters("--follow");
        h.endOptions();
        h.addRelativePaths(filePath);
      }
      else {
        h.endOptions();
      }
      String output = h.run();
      List<GitLogRecord> records = parser.parse(output);

      if (records.isEmpty()) return null;
      // we have information about all changed files of the commit. Extracting information about the file we need.
      GitLogRecord record = records.get(0);
      List<Change> changes = record.parseChanges(myProject, myRoot);
      for (Change change : changes) {
        if ((change.isMoved() || change.isRenamed()) && filePath.equals(notNull(change.getAfterRevision()).getFile())) {
          String[] parents = record.getParentsHashes();
          String parent = parents.length > 0 ? parents[0] : null;
          return Pair.create(parent, notNull(change.getBeforeRevision()).getFile());
        }
      }
      return null;
    }
  }
}
