package com.golias.control_hub.domain.application.action;

import com.golias.control_hub.domain.application.dto.DesiredSpec;
import com.golias.control_hub.domain.application.entities.ApplicationDesiredState;
import com.golias.control_hub.domain.application.exceptions.ApplicationNotFoundException;
import com.golias.control_hub.domain.application.repository.ApplicationDesiredStateRepo;
import com.golias.control_hub.domain.application.repository.ApplicationRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationDesiredStateServiceTests {

    private static final UUID ID = UUID.randomUUID();

    private final ApplicationRepo applicationRepo = mock(ApplicationRepo.class);
    private final ApplicationDesiredStateRepo desiredStateRepo = mock(ApplicationDesiredStateRepo.class);
    private final ApplicationDesiredStateService service = new ApplicationDesiredStateService(applicationRepo,
            desiredStateRepo);

    @BeforeEach
    void setUp() {
        when(applicationRepo.existsById(ID)).thenReturn(true);
        when(desiredStateRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void storesTheDeclaredSpecAgainstTheApplication() {
        var stored = service.set(ID, new DesiredSpec("ghcr.io/api:1.4.2", 2));

        var captor = ArgumentCaptor.forClass(ApplicationDesiredState.class);
        verify(desiredStateRepo).save(captor.capture());
        assertEquals(ID, captor.getValue().getApplicationId());
        assertEquals("ghcr.io/api:1.4.2", stored.image());
        assertEquals(2, stored.replicas());
    }

    @Test
    void eitherFieldAloneIsEnoughToDeclare() {
        assertDoesNotThrow(() -> service.set(ID, new DesiredSpec("ghcr.io/api:1.4.2", null)));
        assertDoesNotThrow(() -> service.set(ID, new DesiredSpec(null, 0)));
    }

    @Test
    void anEmptyOrInvalidSpecIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.set(ID, new DesiredSpec(null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.set(ID, null));
        assertThrows(IllegalArgumentException.class, () -> service.set(ID, new DesiredSpec(" ", 1)));
        assertThrows(IllegalArgumentException.class, () -> service.set(ID, new DesiredSpec("ghcr.io/api:1", -1)));
        verifyNoInteractions(desiredStateRepo);
    }

    @Test
    void readsBackWhatWasDeclared() {
        when(desiredStateRepo.findById(ID)).thenReturn(Optional
                .of(ApplicationDesiredState.builder().applicationId(ID).image("ghcr.io/api:1.4.2").replicas(2).build()));

        var spec = service.get(ID);

        assertEquals("ghcr.io/api:1.4.2", spec.image());
        assertEquals(2, spec.replicas());
    }

    @Test
    void anApplicationWithNothingDeclaredHasNoDesiredState() {
        when(desiredStateRepo.findById(ID)).thenReturn(Optional.empty());

        assertThrows(ApplicationNotFoundException.class, () -> service.get(ID));
    }

    @Test
    void clearingStopsManagingTheApplication() {
        service.clear(ID);

        verify(desiredStateRepo).deleteById(ID);
    }

    @Test
    void everyOperationRequiresARegisteredApplication() {
        var unknown = UUID.randomUUID();
        when(applicationRepo.existsById(unknown)).thenReturn(false);
        var spec = new DesiredSpec("ghcr.io/api:1.4.2", 1);

        assertThrows(ApplicationNotFoundException.class, () -> service.set(unknown, spec));
        assertThrows(ApplicationNotFoundException.class, () -> service.get(unknown));
        assertThrows(ApplicationNotFoundException.class, () -> service.clear(unknown));
        verifyNoInteractions(desiredStateRepo);
    }
}
