package com.example.demo.controller;

import com.example.demo.entity.Episode;
import com.example.demo.entity.Movie;
import com.example.demo.repository.EpisodeRepository;
import com.example.demo.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class MovieController {

    private final MovieRepository movieRepository;
    private final EpisodeRepository episodeRepository;

    // 1. Handles Query: movieById(id: ID!): Movie
    @QueryMapping
    public Movie movieById(@Argument Long id) {
        return movieRepository.findById(id).orElse(null);
    }

    // 2. Handles Query: allMovies: [Movie!]!
    @QueryMapping
    public List<Movie> allMovies() {
        return movieRepository.findAll();
    }

    // 3. Handles Mutation: addMovie(...): Movie!
    @MutationMapping
    public Movie addMovie(@Argument String title, @Argument String genre, @Argument Integer releaseYear) {
        Movie newMovie = Movie.builder()
                .title(title)
                .genre(genre)
                .releaseYear(releaseYear)
                .build();
        return movieRepository.save(newMovie);
    }

    // 4. Handles Field Resolution: Movie.episodes
    // Prevents N+1 queries by bundling episode lookups into a single SQL 'IN' query
    @BatchMapping
    public Map<Movie, List<Episode>> episodes(List<Movie> movies) {
        List<Episode> allEpisodes = episodeRepository.findByMovieIn(movies);
        return allEpisodes.stream()
                .collect(Collectors.groupingBy(Episode::getMovie));
    }
}