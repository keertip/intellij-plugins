package com.intellij.javascript.karma.debug;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.runners.RunContentBuilder;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.javascript.debugger.engine.JSDebugEngine;
import com.intellij.javascript.debugger.execution.RemoteDebuggingFileFinder;
import com.intellij.javascript.debugger.impl.DebuggableFileFinder;
import com.intellij.javascript.debugger.impl.JSDebugProcess;
import com.intellij.javascript.karma.KarmaConfig;
import com.intellij.javascript.karma.execution.KarmaConsoleView;
import com.intellij.javascript.karma.execution.KarmaRunConfiguration;
import com.intellij.javascript.karma.server.KarmaServer;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.PrintWriter;
import java.util.Set;

/**
 * @author Sergey Simonchik
 */
public class KarmaDebugProgramRunner extends GenericProgramRunner {

  @NotNull
  @Override
  public String getRunnerId() {
    return "KarmaJavaScriptTestRunnerDebug";
  }

  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof KarmaRunConfiguration;
  }

  @Nullable
  @Override
  protected RunContentDescriptor doExecute(final Project project,
                                           Executor executor,
                                           RunProfileState state,
                                           RunContentDescriptor contentToReuse,
                                           ExecutionEnvironment env) throws ExecutionException {
    FileDocumentManager.getInstance().saveAllDocuments();
    final ExecutionResult executionResult = state.execute(executor, this);
    if (executionResult == null) {
      return null;
    }
    KarmaConsoleView consoleView = KarmaConsoleView.get(executionResult);
    if (consoleView == null) {
      throw new RuntimeException("KarmaConsoleView was expected!");
    }

    final KarmaServer karmaServer = consoleView.getKarmaRunSession().getKarmaServer();
    if (karmaServer.isReady() && karmaServer.hasCapturedBrowsers()) {
      return doStart(project, karmaServer, executionResult, contentToReuse, env);
    }
    RunContentBuilder contentBuilder = new RunContentBuilder(project, this, executor, executionResult, env);
    final RunContentDescriptor descriptor = contentBuilder.showRunContent(contentToReuse);
    karmaServer.doWhenReadyWithCapturedBrowser(new Runnable() {
      @Override
      public void run() {
        descriptor.getRestarter().run();
      }
    });
    return descriptor;
  }

  private <Connection> RunContentDescriptor doStart(
    @NotNull final Project project,
    @NotNull KarmaServer karmaServer,
    @NotNull final ExecutionResult executionResult,
    @Nullable RunContentDescriptor contentToReuse,
    @NotNull ExecutionEnvironment env) throws ExecutionException {
    final JSDebugEngine<Connection> debugEngine = getDebugEngine(karmaServer.getCapturedBrowsers());
    if (debugEngine == null) {
      throw new ExecutionException("No debuggable browser found");
    }
    if (!debugEngine.prepareDebugger(project)) {
      return null;
    }
    final Connection connection = debugEngine.openConnection(false);
    final String url = "http://localhost:" + karmaServer.getWebServerPort();

    final DebuggableFileFinder fileFinder = getDebuggableFileFinder(karmaServer);
    XDebugSession session = XDebuggerManager.getInstance(project).startSession(
      this,
      env,
      contentToReuse,
      new XDebugProcessStarter() {
        @NotNull
        public XDebugProcess start(@NotNull final XDebugSession session) {
          return debugEngine.createDebugProcess(session, fileFinder, connection, url, executionResult);
        }
      }
    );
    // must be here, after all breakpoints were queued
    ((JSDebugProcess)session.getDebugProcess()).getConnection().queueRequest(new Runnable() {
      @Override
      public void run() {
        resumeTestRunning(executionResult.getProcessHandler());
      }
    });
    return session.getRunContentDescriptor();
  }

  private static DebuggableFileFinder getDebuggableFileFinder(@NotNull KarmaServer karmaServer) {
    BiMap<String, VirtualFile> mappings = HashBiMap.create();
    KarmaConfig karmaConfig = karmaServer.getKarmaConfig();
    if (karmaConfig != null) {
      File basePath = new File(karmaConfig.getBasePath());
      VirtualFile vBasePath = VfsUtil.findFileByIoFile(basePath, false);
      if (vBasePath != null && vBasePath.isValid()) {
        mappings.put("http://localhost:" + karmaServer.getWebServerPort() + "/base",
                     vBasePath);
      }
    }
    mappings.put("http://localhost:" + karmaServer.getWebServerPort() + "/absolute",
                 LocalFileSystem.getInstance().getRoot());
    return new RemoteDebuggingFileFinder(mappings, false);
  }

  @Nullable
  private static <C> JSDebugEngine<C> getDebugEngine(@NotNull Set<String> capturedBrowsers) {
    //noinspection unchecked
    JSDebugEngine<C>[] engines = (JSDebugEngine<C>[])JSDebugEngine.getEngines();
    Set<JSDebugEngine<C>> capturedEngines = ContainerUtil.newHashSet();
    for (JSDebugEngine<C> engine : engines) {
      for (String capturedBrowserName : capturedBrowsers) {
        if (capturedBrowserName.contains(engine.getBrowserFamily().getName())) {
          capturedEngines.add(engine);
          break;
        }
      }
    }
    if (capturedEngines.isEmpty()) {
      return null;
    }
    return capturedEngines.iterator().next();
  }

  private static void resumeTestRunning(@NotNull ProcessHandler processHandler) {
    if (processHandler instanceof OSProcessHandler) {
      // process's input stream will be closed on process termination
      @SuppressWarnings({"IOResourceOpenedButNotSafelyClosed", "ConstantConditions"})
      PrintWriter writer = new PrintWriter(processHandler.getProcessInput());
      writer.print("resume-test-running\n");
      writer.flush();
    }
  }

}
