package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R20: first name, last name and phone, set only by the player. */
@DatabaseTest
class UserProfileTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private UserRepository userRepository;

    private User player;
    private String token;

    @BeforeEach
    void setUp() {
        player = data.user("Telegram Name");
        player.setPhone(null);
        userRepository.save(player);
        token = data.bearer(player);
    }

    private ResultActions patchMe(String json) throws Exception {
        return mvc.perform(patch("/api/users/me").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void aNewProfileHasNoFirstOrLastNameUntilThePlayerSetsThem() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", token))
                .andExpect(jsonPath("$.data.name").value("Telegram Name"))
                .andExpect(jsonPath("$.data.firstName").doesNotExist())
                .andExpect(jsonPath("$.data.lastName").doesNotExist())
                .andExpect(jsonPath("$.data.phone").doesNotExist());
    }

    @Test
    void settingTheNamesAndPhoneRewritesTheDisplayName() throws Exception {
        patchMe("{\"firstName\":\"  Jasur \",\"lastName\":\"Toshmatov\",\"phone\":\"+998901234567\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstName").value("Jasur"))
                .andExpect(jsonPath("$.data.lastName").value("Toshmatov"))
                .andExpect(jsonPath("$.data.name").value("Jasur Toshmatov"))
                .andExpect(jsonPath("$.data.phone").value("+998901234567"));

        // Only the last name: the display name follows, the first name stays.
        patchMe("{\"lastName\":\"Aliyev\"}")
                .andExpect(jsonPath("$.data.firstName").value("Jasur"))
                .andExpect(jsonPath("$.data.name").value("Jasur Aliyev"));
        assertThat(userRepository.findById(player.getId()).orElseThrow().getLastName()).isEqualTo("Aliyev");
    }

    @Test
    void invalidValuesAre400WithTheirOwnMessage() throws Exception {
        patchMe("{\"firstName\":\"   \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("firstName must be between 1 and 60 characters"));
        patchMe("{\"lastName\":\"" + "x".repeat(61) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("lastName must be between 1 and 60 characters"));
        for (String phone : new String[] {"998901234567", "+99890123456", "+7901234567", "+998 90 123 45 67"}) {
            patchMe("{\"phone\":\"" + phone + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("phone must be in the format +998XXXXXXXXX"));
        }
        // Nothing was written.
        User unchanged = userRepository.findById(player.getId()).orElseThrow();
        assertThat(unchanged.getFirstName()).isNull();
        assertThat(unchanged.getPhone()).isNull();
    }

    @Test
    void anotherAccountsPhoneIs409() throws Exception {
        User other = data.user("Other");
        other.setPhone("+998907654321");
        userRepository.save(other);

        patchMe("{\"phone\":\"+998907654321\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_TAKEN"))
                .andExpect(jsonPath("$.message").value("Phone is already used by another account"));

        // Saving one's own phone again is fine.
        patchMe("{\"phone\":\"+998901111111\"}").andExpect(status().isOk());
        patchMe("{\"phone\":\"+998901111111\"}").andExpect(status().isOk());
    }
}
