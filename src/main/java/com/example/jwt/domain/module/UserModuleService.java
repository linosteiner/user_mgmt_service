package com.example.jwt.domain.module;

import com.example.jwt.domain.user.UserService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Assigns modules to users. The assignments themselves live in the module_service's MySQL
 * database; this service has no connection to it and only talks to the module_service's API.
 */
@Service
public class UserModuleService {

  private final UserService userService;
  private final ModuleServiceClient moduleServiceClient;

  public UserModuleService(UserService userService, ModuleServiceClient moduleServiceClient) {
    this.userService = userService;
    this.moduleServiceClient = moduleServiceClient;
  }

  /**
   * The user must exist here (NoSuchElementException, 404), and the module must be available
   * in the module_service (ModuleNotFoundException, 404) -- only then is the assignment written.
   */
  public UserModuleAssignmentDTO assign(UUID userId, UUID moduleId) {
    userService.findById(userId);
    ModuleDTO module = moduleServiceClient.findModule(moduleId);
    moduleServiceClient.assignModule(userId, moduleId);
    return new UserModuleAssignmentDTO(userId, module);
  }

  public List<ModuleDTO> findAvailableModules() {
    return moduleServiceClient.findModules();
  }

  public List<ModuleDTO> findModulesOfUser(UUID userId) {
    userService.findById(userId);
    return moduleServiceClient.findModulesOfUser(userId);
  }
}
