package com.example.jwt.domain.module;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The modules a user can be assigned, read from the module_service. Without it a client has no
 * way to learn a module's id: the module_service is only reachable inside the cluster. Open to
 * every authenticated user, like the list of users.
 */
@RestController
@RequestMapping("/modules")
public class ModuleController {

  private final UserModuleService userModuleService;

  public ModuleController(UserModuleService userModuleService) {
    this.userModuleService = userModuleService;
  }

  @GetMapping({"", "/"})
  public ResponseEntity<List<ModuleDTO>> retrieveAll() {
    return ResponseEntity.ok(userModuleService.findAvailableModules());
  }
}
