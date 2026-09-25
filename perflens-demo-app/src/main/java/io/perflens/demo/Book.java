package io.perflens.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** A book, owned by one author. */
@Entity
public class Book {

    @Id
    private Long id;

    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    private Author author;

    protected Book() {
    }

    public String getTitle() {
        return title;
    }
}
