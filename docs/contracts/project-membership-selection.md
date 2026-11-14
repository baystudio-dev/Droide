# Current membership and selection admission

Two actual shared-store manager failures were reproduced against581:
1. ManagerB selectedP without changing the catalog. Stale managerA still believed Default was active and removedP from workspace membership.
2. ManagerA legitimately removedP. Stale managerB selectedP from its cached catalog because there were no relocation records to trigger the older registry-history comparison.

ProjectManager now checks persisted active selection before removing workspace membership, matching the existing retained-deletion precondition. Selection separately reads the authoritative catalog under the existing registry lock before any active-ID write or validate/publish callback. Versioned stores must match both the known revision and records; nonversioned stores must match loaded records. It refuses stale state instead of silently refreshing the manager or falling back to Default.

These are preconditions for selection eligibility, not evidence of a publication commit or global runtime/process closure. No guard is reset, no workspace files are moved/deleted, no default/journal schema is activated, and no visual UI behavior is rewritten. Read exceptions, cancellation and fatal errors propagate before selection side effects. Cooperative removal/selection orderings share the original lock.

Root selection revalidates current workspace authority before using cached models. Persisted active IDs and cross-manager handoffs remain bound to their recorded owners.

Testing uses fresh synthetic roots, actual shared FileProjectStore managers and a selected JournaledProjectStore pending-state regression. No actual user data cleanup, device/NDK run, arbitrary external-writer exclusion, or broad Explorer closure is claimed. Explorer managed-recovery classification is a separate audit and is not changed here.
