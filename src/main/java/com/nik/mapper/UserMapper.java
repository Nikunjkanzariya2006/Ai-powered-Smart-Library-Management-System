package com.nik.mapper;

import com.nik.model.User;
import com.nik.payload.dto.GenreDTO;
import com.nik.payload.dto.UserDTO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserMapper {

    public static UserDTO toDTO(User user) {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setEmail(user.getEmail());
        userDTO.setFullName(user.getFullName());
        userDTO.setPhone(user.getPhone());
        userDTO.setProfileImage(user.getProfileImage());
        userDTO.setCreatedAt(user.getCreatedAt());
        userDTO.setUpdatedAt(user.getUpdatedAt());
        userDTO.setLastLogin(user.getLastLogin());
        userDTO.setRole(user.getRole());
        userDTO.setVerified(user.getVerified());
        List<GenreDTO> interestGenres = user.getInterestGenres().stream()
                .sorted(Comparator.comparing(genre -> genre.getDisplayOrder() == null ? Integer.MAX_VALUE : genre.getDisplayOrder()))
                .map(genre -> {
                    GenreDTO dto = new GenreDTO();
                    dto.setId(genre.getId());
                    dto.setCode(genre.getCode());
                    dto.setName(genre.getName());
                    dto.setDescription(genre.getDescription());
                    dto.setDisplayOrder(genre.getDisplayOrder());
                    dto.setActive(genre.getActive());
                    dto.setHierarchyPath(genre.getHierarchyPath());
                    dto.setHierarchyLevel(genre.getHierarchyLevel());
                    dto.setParentGenreId(genre.getParentGenre() != null ? genre.getParentGenre().getId() : null);
                    dto.setParentGenreName(genre.getParentGenre() != null ? genre.getParentGenre().getName() : null);
                    return dto;
                })
                .toList();
        userDTO.setInterestGenres(interestGenres);
        userDTO.setInterestGenreIds(
                interestGenres.stream()
                        .map(GenreDTO::getId)
                        .toList()
        );

        return userDTO;
    }

    public static List<UserDTO> toDTOList(List<User> users) {
        return users.stream()
                .map(UserMapper::toDTO)
                .collect(Collectors.toList());
    }

    public static Set<UserDTO> toDTOSet(Set<User> users) {
        return users.stream()
                .map(UserMapper::toDTO)
                .collect(Collectors.toSet());
    }

    public static User toEntity(UserDTO userDTO) {
        User createdUser = new User();
        createdUser.setEmail(userDTO.getEmail());
        createdUser.setPassword(userDTO.getPassword());
        createdUser.setCreatedAt(LocalDateTime.now());
        createdUser.setPhone(userDTO.getPhone());
        createdUser.setFullName(userDTO.getFullName());
        createdUser.setProfileImage(userDTO.getProfileImage());
        createdUser.setRole(userDTO.getRole());
        createdUser.setVerified(Boolean.TRUE.equals(userDTO.getVerified()));

        return createdUser;
    }
}

