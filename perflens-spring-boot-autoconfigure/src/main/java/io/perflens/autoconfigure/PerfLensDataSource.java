package io.perflens.autoconfigure;

import io.perflens.core.context.PerfLensTraceContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * DataSource wrapper that records JDBC execution timing under the active trace.
 *
 * <p>The wrapper keeps SQL capture independent from Hibernate. Native JDBC,
 * Spring JDBC, JPA, and Hibernate all eventually execute through the DataSource,
 * making this the most reliable source for per-call query counts and timings.</p>
 */
public final class PerfLensDataSource extends AbstractDataSource {

    private final DataSource delegate;

    public PerfLensDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws java.sql.SQLException {
        return proxyConnection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws java.sql.SQLException {
        return proxyConnection(delegate.getConnection(username, password));
    }

    private Connection proxyConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(connection.getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, new ConnectionHandler(connection));
    }

    private static final class ConnectionHandler implements InvocationHandler {

        private final Connection target;

        private ConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("unwrap".equals(name) && args != null && args.length == 1) {
                return ((Class<?>) args[0]).isInstance(target) ? target : target.unwrap((Class<?>) args[0]);
            }
            if ("isWrapperFor".equals(name) && args != null && args.length == 1) {
                return ((Class<?>) args[0]).isInstance(target) || target.isWrapperFor((Class<?>) args[0]);
            }
            Object result = invokeTarget(method, target, args);
            if (result instanceof CallableStatement callableStatement && "prepareCall".equals(name)) {
                return proxyStatement(callableStatement, (String) args[0], CallableStatement.class);
            }
            if (result instanceof PreparedStatement preparedStatement && "prepareStatement".equals(name)) {
                return proxyStatement(preparedStatement, (String) args[0], PreparedStatement.class);
            }
            if (result instanceof Statement statement && "createStatement".equals(name)) {
                return proxyStatement(statement, null, Statement.class);
            }
            return result;
        }
    }

    private static Object proxyStatement(Statement statement, String preparedSql, Class<?> statementInterface) {
        return Proxy.newProxyInstance(statement.getClass().getClassLoader(), new Class<?>[] {statementInterface},
                new StatementHandler(statement, preparedSql));
    }

    private static final class StatementHandler implements InvocationHandler {

        private final Statement target;
        private final String preparedSql;

        private StatementHandler(Statement target, String preparedSql) {
            this.target = target;
            this.preparedSql = preparedSql;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String sql = sqlFor(method, args);
            if (sql == null) {
                return invokeTarget(method, target, args);
            }
            long start = System.nanoTime();
            try {
                Object result = invokeTarget(method, target, args);
                PerfLensTraceContext.recordSql(sql, System.nanoTime() - start, true, null);
                return result;
            }
            catch (Throwable ex) {
                PerfLensTraceContext.recordSql(sql, System.nanoTime() - start, false, ex.getMessage());
                throw ex;
            }
        }

        private String sqlFor(Method method, Object[] args) {
            if (!method.getName().startsWith("execute")) {
                return null;
            }
            if (preparedSql != null) {
                return preparedSql;
            }
            if (args != null && args.length > 0 && args[0] instanceof String sql) {
                return sql;
            }
            return null;
        }
    }

    private static Object invokeTarget(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException ex) {
            throw ex.getTargetException();
        }
    }
}
