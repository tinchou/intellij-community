package org.jetbrains.idea.svn.update;

import com.intellij.openapi.vcs.VcsException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.svn.api.BaseSvnClient;
import org.jetbrains.idea.svn.commandLine.SvnBindException;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;

import java.io.File;

public class SvnKitRelocateClient extends BaseSvnClient implements RelocateClient {

  @Override
  public void relocate(@NotNull File copyRoot, @NotNull SVNURL fromPrefix, @NotNull SVNURL toPrefix) throws VcsException {
    try {
      myVcs.getSvnKitManager().createUpdateClient().doRelocate(copyRoot, fromPrefix, toPrefix, true);
    }
    catch (SVNException e) {
      throw new SvnBindException(e);
    }
  }
}
