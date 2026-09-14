package in.bluebustickets.bluebus.identity.domain;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Embeddable;

@Embeddable
public record UserRoleId(UUID user, UUID role) implements Serializable { }
