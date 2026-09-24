package com.example.jwt.domain.module;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module assignments of a user. A user may manage their own modules; managing someone else's
 * needs USER_MODIFY, the authority that editing another user requires as well.
 */
@RestController
@RequestMapping("/users/{userId}/modules")
public class UserModuleController {

  private static final String SELF_OR_USER_MODIFY =
      "hasAuthority('USER_MODIFY') || #userId == authentication.principal.user.id";

  private final UserModuleService userModuleService;

  public UserModuleController(UserModuleService userModuleService) {
    this.userModuleService = userModuleService;
  }

  /** Idempotent: assigning the same module again keeps one assignment and answers 200. */
  @PutMapping("/{moduleId}")
  @PreAuthorize(SELF_OR_USER_MODIFY)
  public ResponseEntity<UserModuleAssignmentDTO> assign(@PathVariable UUID userId,
      @PathVariable UUID moduleId) {
    return ResponseEntity.ok(userModuleService.assign(userId, moduleId));
  }

  @GetMapping({"", "/"})
  @PreAuthorize(SELF_OR_USER_MODIFY)
  public ResponseEntity<List<ModuleDTO>> retrieveAll(@PathVariable UUID userId) {
    return ResponseEntity.ok(userModuleService.findModulesOfUser(userId));
  }
}
