package io.perflens.autoconfigure.app;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LibraryService {

    private final AuthorRepository authors;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService audit;

    public LibraryService(AuthorRepository authors, JdbcTemplate jdbcTemplate, AuditService audit) {
        this.authors = authors;
        this.jdbcTemplate = jdbcTemplate;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public int countBooksLazily() {
        return authors.findAll().stream().mapToInt(author -> author.getBooks().size()).sum();
    }

    @Transactional(readOnly = true)
    public int countBooksWithFetchJoin() {
        return authors.findAllWithBooks().stream().mapToInt(author -> author.getBooks().size()).sum();
    }

    public int countAuthorsTwice() {
        Integer first = jdbcTemplate.queryForObject("select count(*) from author where name <> ?", Integer.class, "x");
        Integer second = jdbcTemplate.queryForObject("select count(*) from author where name <> ?", Integer.class, "x");
        return first + second;
    }

    @Transactional
    public void slowTransaction() throws InterruptedException {
        jdbcTemplate.queryForObject("select count(*) from author", Integer.class);
        Thread.sleep(80);
    }

    @Transactional
    public void nestedTransaction() {
        jdbcTemplate.queryForObject("select count(*) from author", Integer.class);
        audit.record();
    }

    @Service
    public static class AuditService {

        private final JdbcTemplate jdbcTemplate;

        public AuditService(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void record() {
            jdbcTemplate.queryForObject("select count(*) from book", Integer.class);
        }
    }

    public List<String> names() {
        return authors.findAll().stream().map(Author::getName).toList();
    }
}
