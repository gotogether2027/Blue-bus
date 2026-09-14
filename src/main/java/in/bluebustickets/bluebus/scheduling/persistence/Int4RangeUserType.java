package in.bluebustickets.bluebus.scheduling.persistence;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import in.bluebustickets.bluebus.scheduling.domain.Int4Range;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;
import org.postgresql.util.PGobject;

/**
 * Maps domain {@link Int4Range} to PostgreSQL {@code int4range} via the JDBC driver {@link PGobject}.
 * Avoids an extra library dependency for a single range type.
 */
public class Int4RangeUserType implements UserType<Int4Range> {

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<Int4Range> returnedClass() {
        return Int4Range.class;
    }

    @Override
    public boolean equals(Int4Range left, Int4Range right) {
        return left == null ? right == null : left.equals(right);
    }

    @Override
    public int hashCode(Int4Range value) {
        return value == null ? 0 : value.hashCode();
    }

    @Override
    public Int4Range nullSafeGet(
            ResultSet resultSet,
            int position,
            SharedSessionContractImplementor session,
            Object owner) throws SQLException {
        Object value = resultSet.getObject(position);
        if (value == null) {
            return null;
        }
        return Int4Range.parsePostgresLiteral(value.toString());
    }

    @Override
    public void nullSafeSet(
            PreparedStatement statement,
            Int4Range value,
            int index,
            SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.OTHER);
            return;
        }
        PGobject pgObject = new PGobject();
        pgObject.setType("int4range");
        pgObject.setValue(value.toPostgresLiteral());
        statement.setObject(index, pgObject);
    }

    @Override
    public Int4Range deepCopy(Int4Range value) {
        return value;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(Int4Range value) {
        return value;
    }

    @Override
    public Int4Range assemble(Serializable cached, Object owner) {
        return (Int4Range) cached;
    }
}
