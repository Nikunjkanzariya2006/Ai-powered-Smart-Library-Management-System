package com.nik.mapper;

import com.nik.model.Genre;
import com.nik.payload.dto.GenreDTO;
import com.nik.repository.GenreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper for converting between Genre entity and GenreDTO
 */
@Component
@RequiredArgsConstructor
public class GenreMapper {

    private final GenreRepository genreRepository;

    /**
     * Convert Genre entity to GenreDTO
     */
    public GenreDTO toDTO(Genre genre) {
        return toDTO(genre, false);
    }

    /**
     * Convert Genre entity to GenreDTO with option to include sub-genres
     */
    public GenreDTO toDTO(Genre genre, 
    boolean includeSubGenres) {
        if (genre == null) {
            return null;
        }

        GenreDTO dto = new GenreDTO();
        dto.setId(genre.getId());
        dto.setCode(genre.getCode());
        dto.setName(genre.getName());
        dto.setDescription(genre.getDescription());
        dto.setDisplayOrder(genre.getDisplayOrder());
        dto.setActive(genre.getActive());
        dto.setClassificationSystem(genre.getClassificationSystem());
        dto.setClassificationCode(genre.getClassificationCode());
        dto.setHierarchyPath(genre.getHierarchyPath());
        dto.setHierarchyLevel(genre.getHierarchyLevel());
        dto.setCreatedAt(genre.getCreatedAt());
        dto.setUpdatedAt(genre.getUpdatedAt());

        // Set parent genre info if exists
        if (genre.getParentGenre() != null) {
            dto.setParentGenreId(genre.getParentGenre().getId());
            dto.setParentGenreName(genre.getParentGenre().getName());
        }

        // Include sub-genres if requested (recursively)
        if (includeSubGenres && genre.getSubGenres() != null && !genre.getSubGenres().isEmpty()) {
            dto.setSubGenres(genre.getSubGenres().stream()
                .filter(subGenre -> subGenre.getActive()) // Only include active sub-genres
                .map(subGenre -> toDTO(subGenre, true)) // Recursive: include sub-genres of sub-genres
                .collect(Collectors.toList()));
        }

        // Set book count
        dto.setBookCount((long) (genre.getBooks() != null ? genre.getBooks().size() : 0));

        return dto;
    }

    /**
     * Convert GenreDTO to Genre entity
     */
    public Genre toEntity(GenreDTO dto) {
        if (dto == null) {
            return null;
        }

        Genre genre = new Genre();
        genre.setId(dto.getId());
        genre.setCode(dto.getCode());
        genre.setName(dto.getName());
        genre.setDescription(dto.getDescription());
        genre.setDisplayOrder(dto.getDisplayOrder() != null ? dto.getDisplayOrder() : 0);
        genre.setClassificationSystem(dto.getClassificationSystem());
        genre.setClassificationCode(dto.getClassificationCode());
        genre.setHierarchyPath(dto.getHierarchyPath());
        genre.setHierarchyLevel(dto.getHierarchyLevel() != null ? dto.getHierarchyLevel() : 0);

        if (dto.getActive() != null) {
            genre.setActive(dto.getActive());
        } else {
            genre.setActive(true); // Default to active
        }

        // Set parent genre if provided
        if (dto.getParentGenreId() != null) {
            genreRepository.findById(dto.getParentGenreId())
                .ifPresent(genre::setParentGenre);
        }

        return genre;
    }

    /**
     * Update existing Genre entity with data from GenreDTO (for update operations)
     */
    public void updateEntityFromDTO(GenreDTO dto, Genre genre) {
        if (dto == null || genre == null) {
            return;
        }

        genre.setCode(dto.getCode());
        genre.setName(dto.getName());
        genre.setDescription(dto.getDescription());
        genre.setDisplayOrder(dto.getDisplayOrder() != null ? dto.getDisplayOrder() : 0);
        genre.setClassificationSystem(dto.getClassificationSystem());
        genre.setClassificationCode(dto.getClassificationCode());
        genre.setHierarchyPath(dto.getHierarchyPath());
        genre.setHierarchyLevel(dto.getHierarchyLevel() != null ? dto.getHierarchyLevel() : genre.getHierarchyLevel());

        if (dto.getActive() != null) {
            genre.setActive(dto.getActive());
        }

        // Update parent genre if provided
        if (dto.getParentGenreId() != null) {
            genreRepository.findById(dto.getParentGenreId())
                .ifPresent(genre::setParentGenre);
        }
    }

    /**
     * Convert list of Genre entities to list of GenreDTOs
     */
    public List<GenreDTO> toDTOList(List<Genre> genres) {
        return toDTOList(genres, false);
    }

    /**
     * Convert list of Genre entities to list of GenreDTOs with option to include sub-genres
     */
    public List<GenreDTO> toDTOList(List<Genre> genres, boolean includeSubGenres) {
        if (genres == null) {
            return null;
        }
        return genres.stream()
            .map(genre -> toDTO(genre, includeSubGenres))
            .collect(Collectors.toList());
    }
}

