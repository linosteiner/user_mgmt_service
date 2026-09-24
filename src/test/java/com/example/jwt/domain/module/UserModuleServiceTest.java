package com.example.jwt.domain.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.jwt.domain.user.User;
import com.example.jwt.domain.user.UserService;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The order of checks before an assignment is written. */
class UserModuleServiceTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID MODULE_ID = UUID.randomUUID();

  private UserService userService;
  private ModuleServiceClient client;
  private UserModuleService service;

  @BeforeEach
  void setUp() {
    userService = mock(UserService.class);
    client = mock(ModuleServiceClient.class);
    service = new UserModuleService(userService, client);
  }

  @Test
  void assignsAnAvailableModule() {
    ModuleDTO module = new ModuleDTO(MODULE_ID, "CLOUD-ARCH", "Cloud Architecture", null);
    when(userService.findById(USER_ID)).thenReturn(new User());
    when(client.findModule(MODULE_ID)).thenReturn(module);

    UserModuleAssignmentDTO assignment = service.assign(USER_ID, MODULE_ID);

    assertThat(assignment).isEqualTo(new UserModuleAssignmentDTO(USER_ID, module));
    verify(client).assignModule(USER_ID, MODULE_ID);
  }

  @Test
  void unknownUserStopsBeforeTheModuleService() {
    when(userService.findById(USER_ID)).thenThrow(new NoSuchElementException("No value present"));

    assertThatThrownBy(() -> service.assign(USER_ID, MODULE_ID))
        .isInstanceOf(NoSuchElementException.class);
    verify(client, never()).findModule(any());
    verify(client, never()).assignModule(any(), any());
  }

  @Test
  void unavailableModuleIsNotAssigned() {
    when(userService.findById(USER_ID)).thenReturn(new User());
    when(client.findModule(MODULE_ID)).thenThrow(new ModuleNotFoundException(MODULE_ID));

    assertThatThrownBy(() -> service.assign(USER_ID, MODULE_ID))
        .isInstanceOf(ModuleNotFoundException.class);
    verify(client, never()).assignModule(any(), any());
  }
}
