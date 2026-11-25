package org.eclipse.jgit.internal.storage.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.Repository;

/** Uses the pinned JGit file backend's ref lock and packed-ref deletion for a guarded stash drop. */
public final class DroideStashDrop extends RefDirectoryUpdate {
    private static final String STALE = "Stash list changed. Refresh it and select the stash again.";

    private DroideStashDrop(RefDirectory database, Ref ref) { super(database, ref); }

    public static void drop(Repository repo, int index, String objectId) throws IOException {
        Ref ref = repo.exactRef(Constants.R_STASH);
        if (ref == null || ref.isSymbolic() || !ObjectId.isId(objectId)) throw new IOException(STALE);
        if (!(repo.getRefDatabase() instanceof RefDirectory)) throw new IOException("Unsupported stash ref storage");
        new DroideStashDrop((RefDirectory) repo.getRefDatabase(), ref).remove(repo, index, ObjectId.fromString(objectId));
    }

    private void remove(Repository repo, int index, ObjectId expected) throws IOException {
        try {
            if (!tryLock(false)) throw new IOException("Stash is being updated. Refresh and try again.");
            RefDirectory db = getRefDatabase();
            LockFile logLock = new LockFile(db.logFor(Constants.R_STASH));
            if (!logLock.lock()) throw new IOException("Stash log is being updated. Refresh and try again.");
            try {
                Ref current = repo.exactRef(Constants.R_STASH);
                ReflogReader reader = repo.getReflogReader(Constants.R_STASH);
                if (current == null || current.isSymbolic() || current.getStorage() != getRef().getStorage()
                        || reader == null) throw new IOException(STALE);
                List<ReflogEntry> entries = reader.getReverseEntries();
                if (index < 0 || index >= entries.size() || !expected.equals(entries.get(index).getNewId())
                        || !entries.get(0).getNewId().equals(current.getObjectId())) throw new IOException(STALE);
                if (entries.size() == 1) {
                    // JGit removes loose and packed copies of the ref along with the reflog.
                    super.doDelete(Result.FORCED);
                    return;
                }
                ReflogWriter writer = new ReflogWriter(db, true);
                String lockName = ReflogWriter.refLockFor(Constants.R_STASH);
                ObjectId previous = ObjectId.zeroId();
                for (int i = entries.size() - 1; i >= 0; i--) {
                    if (i == index) continue;
                    ReflogEntry entry = entries.get(i);
                    writer.log(lockName, previous, entry.getNewId(), entry.getWho(), entry.getComment());
                    previous = entry.getNewId();
                }
                // Keep the ref lock across both publications. Until the log commits every old
                // entry is still reachable in it. A failed log replacement restores the old tip.
                Path head = db.fileFor(Constants.R_STASH).toPath();
                boolean changeTip = !previous.equals(current.getObjectId());
                if (changeTip) writeTip(head, previous);
                if (!logLock.commit()) {
                    if (changeTip) writeTip(head, current.getObjectId());
                    throw new IOException("Could not commit the stash log. Refresh before retrying.");
                }
            } finally {
                logLock.unlock();
            }
        } finally {
            unlock();
        }
    }

    private static void writeTip(Path head, ObjectId tip) throws IOException {
        Path staged = Files.createTempFile(head.getParent(), ".droide-stash-", ".tmp");
        try {
            Files.write(staged, Constants.encode(tip.name() + "\n"));
            Files.move(staged, head, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
