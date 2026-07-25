package com.example.demo.repository;

import com.example.demo.entity.Episode;
import com.example.demo.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EpisodeRepository extends JpaRepository<Episode, Long> {

    // Batch query: SELECT * FROM episodes WHERE movie_id IN (...)
    List<Episode> findByMovieIn(List<Movie> movies);
}