package com.example.fgasdk.init;

import com.example.fgasdk.config.FgaProperties;
import com.example.fgasdk.dto.TupleRequest;
import com.example.fgasdk.model.AuthorizationModels;
import com.example.fgasdk.service.FgaModelService;
import com.example.fgasdk.service.FgaStoreService;
import com.example.fgasdk.service.FgaTupleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Auto-bootstraps OpenFGA on startup and seeds demo data for the minimal 3-type model.
 *
 * Schema shape (never changes):
 *   user ──assignee──► role ──{view|edit|delete|manage}_access──► resource
 *                        └──granted_by──► role   (inheritance)
 *                                               └──parent──► resource (hierarchy)
 *
 * Everything below is just tuples:
 *   - Resource tree  : (resource:/home, parent, resource:/home/users)
 *   - Role grants    : (role:admin, manage_access, resource:/home)
 *   - User assignment: (user:alice, assignee, role:admin)
 *   - Role inherit   : (role:viewer, granted_by, role:editor)
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final FgaStoreService storeService;
    private final FgaModelService modelService;
    private final FgaTupleService tupleService;
    private final FgaProperties props;

    public DataInitializer(FgaStoreService storeService, FgaModelService modelService,
                           FgaTupleService tupleService, FgaProperties props) {
        this.storeService = storeService;
        this.modelService = modelService;
        this.tupleService = tupleService;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("=== OpenFGA Dynamic IAM Demo Initializer Starting ===");

        // Step 1: Create store if not already configured
        if (props.getStoreId() == null || props.getStoreId().isBlank()) {
            log.info("No storeId configured — creating new store 'iam-demo'...");
            String storeId = storeService.createStore("iam-demo");
            log.info("Store created: {}", storeId);
        } else {
            log.info("Using existing storeId: {}", props.getStoreId());
        }

        // Step 2: Write the authorization model
        log.info("Writing dynamic RBAC authorization model...");
        String modelId = modelService.writeModel(AuthorizationModels.IAM_MODEL);
        log.info("Model written: {}", modelId);

        // Step 3: Seed demo data
        if (props.isSeedData()) {
            log.info("Seeding demo tuples...");
            seedDemoData();
            log.info("Demo data seeded.");
            printTestingGuide();
        }

        log.info("=== OpenFGA Dynamic IAM Demo Ready — StoreId: {} | ModelId: {} ===",
                props.getStoreId(), props.getModelId());
    }

    private void seedDemoData() throws Exception {

        // ── Resource tree (self-referential parent links) ─────────────────────
        // Any path depth works — no schema change needed for new paths.
        tupleService.writeTuples(List.of(
                tuple("resource:/home",        "parent", "resource:/home/users"),
                tuple("resource:/home",        "parent", "resource:/home/config"),
                tuple("resource:/home/users",  "parent", "resource:/home/users/index.html"),
                tuple("resource:/home/config", "parent", "resource:/home/config/index.html")
        ));
        log.info("  ✓ Resource tree: /home → [/home/users, /home/config] → their index.html files");

        // ── Grant roles permission levels on resources ────────────────────────
        // Role names are invented here at seed time — no schema change needed.
        // manage_access on /home cascades down to ALL children automatically.
        tupleService.writeTuples(List.of(
                tuple("role:admin",  "manage_access", "resource:/home"),
                tuple("role:editor", "edit_access",   "resource:/home/users"),
                tuple("role:viewer", "view_access",   "resource:/home")
        ));
        log.info("  ✓ Roles granted: admin=manage_access(/home), editor=edit_access(/home/users), viewer=view_access(/home)");

        // ── Role inheritance ──────────────────────────────────────────────────
        // role:editor inherits all permissions of role:viewer.
        // Direction: (role:editor, granted_by, role:viewer)
        //   → effective_assignee of role:editor follows granted_by to role:viewer
        //   → so editor users also satisfy role:viewer's view_access grants.
        // NOT the reverse — role:viewer users do NOT get role:editor's edit_access.
        tupleService.writeTuples(List.of(
                tuple("role:editor", "granted_by", "role:viewer")
        ));
        log.info("  ✓ Role inheritance: role:editor granted_by role:viewer (editor gets viewer permissions too)");

        // ── ABAC: time-windowed role grant ────────────────────────────────────
        // role:night-auditor can view /home/config only between 20:00 and 06:00.
        // The window values (start_hour=20, end_hour=6) are stored IN the tuple —
        // the model never needs to change to add or update this rule.
        // At check time the service auto-injects "current_hour" from the server
        // clock if the caller didn't supply it.
        tupleService.writeTuples(List.of(
                tupleWithCondition(
                        "role:night-auditor",
                        "view_access",
                        "resource:/home/config",
                        "within_time_window",
                        Map.of("start_hour", 20, "end_hour", 6))
        ));
        tupleService.writeTuples(List.of(
                tupleWithCondition(
                        "role:tester",
                        "view_access",
                        "resource:/home",
                        "within_time_window",
                        Map.of("start_hour", 6, "end_hour", 20))
        ));
        log.info("  ✓ ABAC: role:night-auditor has view_access on /home/config only between 20:00–06:00");

        // ── User → role assignments ───────────────────────────────────────────
        /*tupleService.writeTuples(List.of(
                tuple("user:alice", "assignee", "role:admin"),
                tuple("user:bob",   "assignee", "role:editor"),
                tuple("user:carol", "assignee", "role:viewer")
        ));*/
        log.info("  ✓ Assignments: alice=admin, bob=editor, carol=viewer");
    }

    private TupleRequest tuple(String user, String relation, String object) {
        TupleRequest t = new TupleRequest();
        t.setUser(user);
        t.setRelation(relation);
        t.setObject(object);
        return t;
    }

    private TupleRequest tupleWithCondition(String user, String relation, String object,
                                            String conditionName, Map<String, Object> conditionContext) {
        TupleRequest t = new TupleRequest();
        t.setUser(user);
        t.setRelation(relation);
        t.setObject(object);
        t.setConditionName(conditionName);
        t.setConditionContext(conditionContext);
        return t;
    }

    private void printTestingGuide() {
        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════════════╗");
        log.info("║           MINIMAL DYNAMIC RBAC — QUICK TEST GUIDE                  ║");
        log.info("╠══════════════════════════════════════════════════════════════════════╣");
        log.info("║ 1. admin cascades to all children:                                  ║");
        log.info("║    POST /api/authz/check                                             ║");
        log.info("║    {\"userId\":\"user:alice\",\"relation\":\"can_manage\",                    ║");
        log.info("║     \"objectType\":\"resource\",\"objectId\":\"/home/users/index.html\"}      ║");
        log.info("║    → true  (alice→admin→manage_access(/home)→cascades down)         ║");
        log.info("║                                                                      ║");
        log.info("║ 2. editor can edit /home/users but NOT /home/config:                 ║");
        log.info("║    {\"userId\":\"user:bob\",\"relation\":\"can_edit\",                         ║");
        log.info("║     \"objectType\":\"resource\",\"objectId\":\"/home/users/index.html\"}      ║");
        log.info("║    → true                                                            ║");
        log.info("║    {\"userId\":\"user:bob\",\"relation\":\"can_edit\",                         ║");
        log.info("║     \"objectType\":\"resource\",\"objectId\":\"/home/config/index.html\"}     ║");
        log.info("║    → false  (editor only has edit_access on /home/users subtree)     ║");
        log.info("║                                                                      ║");
        log.info("║ 3. viewer can view everything under /home:                           ║");
        log.info("║    {\"userId\":\"user:carol\",\"relation\":\"can_view\",                       ║");
        log.info("║     \"objectType\":\"resource\",\"objectId\":\"/home/config/index.html\"}     ║");
        log.info("║    → true                                                            ║");
        log.info("║                                                                      ║");
        log.info("║ 4. NEW ROLE at runtime — zero model changes:                         ║");
        log.info("║    POST /api/tuples                                                  ║");
        log.info("║    [{\"user\":\"role:config-admin\",\"relation\":\"manage_access\",            ║");
        log.info("║      \"object\":\"resource:/home/config\"},                              ║");
        log.info("║     {\"user\":\"user:dave\",\"relation\":\"assignee\",                         ║");
        log.info("║      \"object\":\"role:config-admin\"}]                                  ║");
        log.info("║    → dave can now manage /home/config and its children               ║");
        log.info("║                                                                      ║");
        log.info("║ 5. IdP role injection (no stored assignment needed):                 ║");
        log.info("║    {\"userId\":\"user:frank\",\"relation\":\"can_edit\",                       ║");
        log.info("║     \"objectType\":\"resource\",\"objectId\":\"/home/users/index.html\",      ║");
        log.info("║     \"contextualRoles\":[\"role:editor\"]}                               ║");
        log.info("║    → true  (evaluated inline, never stored)                          ║");
        log.info("║                                                                      ║");
        log.info("║ 6. ABAC time-window — current_hour auto-injected from server clock:  ║");
        log.info("║    {\"userId\":\"user:eve\",\"relation\":\"can_view\",                         ║");
        log.info("║     \"objectType\":\"resource\",\"objectId\":\"/home/config\",                ║");
        log.info("║     \"contextualRoles\":[\"role:night-auditor\"]}                         ║");
        log.info("║    → true only between 20:00–06:00 (server clock injected as         ║");
        log.info("║      current_hour; override by passing context:{\"current_hour\":21})  ║");
        log.info("╚══════════════════════════════════════════════════════════════════════╝");
        log.info("");
    }
}
