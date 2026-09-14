package in.bluebustickets.bluebus.operator.domain;

import java.io.Serializable;
import java.util.UUID;
import jakarta.persistence.Embeddable;

@Embeddable
public record OperatorUserId(UUID operator, UUID user) implements Serializable { }
