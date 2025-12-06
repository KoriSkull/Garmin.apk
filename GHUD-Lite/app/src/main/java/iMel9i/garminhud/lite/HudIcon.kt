package iMel9i.garminhud.lite

/**
 * Defines all possible icons/symbols that can be displayed on the HUD.
 * This maps human-readable names to the Type and Angle bytes required by the protocol.
 */
enum class HudIcon(val id: String, val displayName: String, val type: Int, val angle: Int, val isCamera: Boolean = false) {
    NONE("none", "None", 0x00, 0x00),

    // ==========================================
    // Standard Arrows (Type 0x80 = ArrowOnly)
    // ==========================================
    STRAIGHT("straight", "Straight", 0x80, 0x10),
    LEFT("left", "Left", 0x80, 0x40),
    RIGHT("right", "Right", 0x80, 0x04),
    SHARP_LEFT("sharp_left", "Sharp Left", 0x80, 0x80),
    SHARP_RIGHT("sharp_right", "Sharp Right", 0x80, 0x02),
    EASY_LEFT("easy_left", "Easy Left", 0x80, 0x20),
    EASY_RIGHT("easy_right", "Easy Right", 0x80, 0x08),
    UTURN_LEFT("uturn_left", "U-Turn Left", 0x80, 0x81),
    UTURN_RIGHT("uturn_right", "U-Turn Right", 0x80, 0x82),

    // ==========================================
    // Destination / Flags (Type 0x40 = RightFlag)
    // ==========================================
    DESTINATION_STRAIGHT("dest_straight", "Destination Straight (Flag)", 0x40, 0x10),
    DESTINATION_LEFT("dest_left", "Destination Left (Flag)", 0x40, 0x40),
    DESTINATION_RIGHT("dest_right", "Destination Right (Flag)", 0x40, 0x04),
    DESTINATION_SHARP_LEFT("dest_sharp_left", "Destination Sharp Left (Flag)", 0x40, 0x80),
    DESTINATION_SHARP_RIGHT("dest_sharp_right", "Destination Sharp Right (Flag)", 0x40, 0x02),
    DESTINATION_EASY_LEFT("dest_easy_left", "Destination Easy Left (Flag)", 0x40, 0x20),
    DESTINATION_EASY_RIGHT("dest_easy_right", "Destination Easy Right (Flag)", 0x40, 0x08),

    // ==========================================
    // Lanes (Type 0x01 = Lane, 0x02 = LongerLane)
    // ==========================================
    LANE_STRAIGHT("lane_straight", "Lane: Straight", 0x01, 0x10),
    LANE_LEFT("lane_left", "Lane: Left", 0x01, 0x40),
    LANE_RIGHT("lane_right", "Lane: Right", 0x01, 0x04),
    LANE_LONG_STRAIGHT("lane_long_straight", "Lane Long: Straight", 0x02, 0x10),

    // ==========================================
    // Roundabouts (Left Traffic - Type 0x04)
    // ==========================================
    // Angles for roundabouts often act as exit indicators or visual rotation
    RND_LEFT_STRAIGHT("rnd_left_straight", "Rnd Left (Straight)", 0x04, 0x10),
    RND_LEFT_LEFT("rnd_left_left", "Rnd Left (Left)", 0x04, 0x40),
    RND_LEFT_RIGHT("rnd_left_right", "Rnd Left (Right)", 0x04, 0x04),
    RND_LEFT_SHARP_LEFT("rnd_left_sharp_left", "Rnd Left (Sharp Left)", 0x04, 0x80),
    RND_LEFT_SHARP_RIGHT("rnd_left_sharp_right", "Rnd Left (Sharp Right)", 0x04, 0x02),
    RND_LEFT_BACK("rnd_left_back", "Rnd Left (Back)", 0x04, 0x81), // U-turnish
    
    // ==========================================
    // Roundabouts (Right Traffic - Type 0x08)
    // ==========================================
    RND_RIGHT_STRAIGHT("rnd_right_straight", "Rnd Right (Straight)", 0x08, 0x10),
    RND_RIGHT_LEFT("rnd_right_left", "Rnd Right (Left)", 0x08, 0x40),
    RND_RIGHT_RIGHT("rnd_right_right", "Rnd Right (Right)", 0x08, 0x04),
    RND_RIGHT_SHARP_LEFT("rnd_right_sharp_left", "Rnd Right (Sharp Left)", 0x08, 0x80),
    RND_RIGHT_SHARP_RIGHT("rnd_right_sharp_right", "Rnd Right (Sharp Right)", 0x08, 0x02),
    RND_RIGHT_BACK("rnd_right_back", "Rnd Right (Back)", 0x08, 0x82),

    // ==========================================
    // Special
    // ==========================================
    CAMERA("camera", "Speed Camera", 0x80, 0x00, true); // Uses special flag in setSpeedWithLimit

    companion object {
        fun fromId(id: String): HudIcon? = values().find { it.id == id }
    }
}
