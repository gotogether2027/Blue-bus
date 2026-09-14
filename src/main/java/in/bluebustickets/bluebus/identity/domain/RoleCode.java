package in.bluebustickets.bluebus.identity.domain;

public enum RoleCode {
    SUPER_ADMIN(RoleScope.PLATFORM),
    ADMIN(RoleScope.PLATFORM),
    CUSTOMER(RoleScope.PLATFORM),
    OPERATOR_ADMIN(RoleScope.OPERATOR),
    OPERATOR_STAFF(RoleScope.OPERATOR);

    private final RoleScope requiredScope;

    RoleCode(RoleScope requiredScope) {
        this.requiredScope = requiredScope;
    }

    public RoleScope requiredScope() {
        return requiredScope;
    }
}
