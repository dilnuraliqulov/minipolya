package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.request.UpdateUserRequest;
import uz.minipolya.minipolya_backend.dto.response.UserResponse;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.UserMapper;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.UUID;

/**
 * Profile read/update for the authenticated user.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        return userMapper.toResponse(findOrThrow(userId));
    }

    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateUserRequest request) {
        User user = findOrThrow(userId);
        userMapper.updateUserFromRequest(request, user);
        return userMapper.toResponse(userRepository.save(user));
    }

    private User findOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
