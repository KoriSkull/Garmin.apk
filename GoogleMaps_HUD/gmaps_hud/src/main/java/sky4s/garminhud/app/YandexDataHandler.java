package sky4s.garminhud.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.core.content.ContextCompat;

import sky4s.garminhud.eOutAngle;
import sky4s.garminhud.eOutType;
import sky4s.garminhud.eUnits;

/**
 * Yandex Data Handler - registers broadcast receiver and sends data to HUD
 * Call registerYandexReceiver() in MainActivity.onCreate() after all other
 * receivers
 */
public class YandexDataHandler {

    private static final String TAG = "YandexDataHandler";
    private MainActivity mainActivity;
    private BroadcastReceiver yandexReceiver;
    private eOutAngle lastAngle = eOutAngle.AsDirection;

    public YandexDataHandler(MainActivity activity) {
        this.mainActivity = activity;
    }

    public void registerReceiver() {
        yandexReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String speedLimit = intent.getStringExtra("SPEED_LIMIT");
                String distance = intent.getStringExtra("DISTANCE");
                String eta = intent.getStringExtra("ETA");
                String remainTime = intent.getStringExtra("REMAIN_TIME");
                String maneuverDesc = intent.getStringExtra("MANEUVER_DESC");
                boolean isNavigating = intent.getBooleanExtra("IS_NAVIGATING", false);

                // Update navigation status
                mainActivity.mIsNavigating = isNavigating;

                if (mainActivity.mHud != null && isNavigating) {
                    // Parse distance
                    if (distance != null) {
                        try {
                            String[] parts = distance.split("\\s+");
                            if (parts.length >= 2) {
                                float distValue = Float.parseFloat(parts[0].replace(",", "."));
                                String unit = parts[1].toLowerCase();

                                eUnits hudUnit = unit.contains("км") || unit.equals("km") ? eUnits.Kilometres
                                        : eUnits.Metres;

                                mainActivity.mHud.setDistance(distValue, hudUnit);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing distance: " + e.getMessage());
                        }
                    }

                    // Parse remaining time
                    if (remainTime != null) {
                        try {
                            String numStr = remainTime.replaceAll("[^0-9]", "");
                            if (!numStr.isEmpty()) {
                                int minutes = Integer.parseInt(numStr);
                                int hours = minutes / 60;
                                int mins = minutes % 60;
                                mainActivity.mHud.setRemainTime(hours, mins, false);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing remain time: " + e.getMessage());
                        }
                    }

                    // Parse Speed Limit
                    if (speedLimit != null) {
                        try {
                            int limit = Integer.parseInt(speedLimit.replaceAll("[^0-9]", ""));
                            // Assuming current speed is not available here, passing 0 as current speed
                            // and false for speeding warning.
                            // If we want to show just the limit sign, setSpeedWarning might be used.
                            mainActivity.mHud.setSpeedWarning(0, limit, false, true, true);
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing speed limit: " + e.getMessage());
                        }
                    }

                    // Parse maneuver arrow
                    if (maneuverDesc != null) {
                        try {
                            updateHudDirection(maneuverDesc);
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing maneuver: " + e.getMessage());
                        }
                    }
                }
            }

            private void updateHudDirection(String desc) {
                String maneuver = desc.toUpperCase();
                eOutAngle angle = eOutAngle.Straight;
                eOutType type = eOutType.Lane;
                eOutAngle roundaboutOut = eOutAngle.AsDirection;

                // Handle UTURN first to avoid matching LEFT/RIGHT from UTURN_LEFT/UTURN_RIGHT
                if (maneuver.contains("UTURN")) {
                    angle = maneuver.contains("RIGHT") ? eOutAngle.RightDown : eOutAngle.LeftDown;
                } else if (maneuver.contains("ROUNDABOUT")) {
                    boolean rightRoundabout = maneuver.contains("RIGHT");
                    type = rightRoundabout ? eOutType.RightRoundabout : eOutType.LeftRoundabout;

                    if (maneuver.contains("SHARP")) {
                        angle = rightRoundabout ? eOutAngle.SharpRight : eOutAngle.SharpLeft;
                    } else if (maneuver.contains("EASY") || maneuver.contains("SLIGHT")) {
                        angle = rightRoundabout ? eOutAngle.EasyRight : eOutAngle.EasyLeft;
                    } else if (maneuver.contains("STRAIGHT")) {
                        angle = eOutAngle.Straight;
                    } else {
                        angle = rightRoundabout ? eOutAngle.Right : eOutAngle.Left;
                    }
                    roundaboutOut = angle;
                } else if (maneuver.contains("LEFT")) {
                    angle = eOutAngle.Left;
                    if (maneuver.contains("SHARP"))
                        angle = eOutAngle.SharpLeft;
                    if (maneuver.contains("EASY") || maneuver.contains("SLIGHT"))
                        angle = eOutAngle.EasyLeft;
                } else if (maneuver.contains("RIGHT")) {
                    angle = eOutAngle.Right;
                    if (maneuver.contains("SHARP"))
                        angle = eOutAngle.SharpRight;
                    if (maneuver.contains("EASY") || maneuver.contains("SLIGHT"))
                        angle = eOutAngle.EasyRight;
                } else if (maneuver.contains("STRAIGHT")) {
                    angle = eOutAngle.Straight;
                }

                if (angle != lastAngle) {
                    lastAngle = angle;
                    Log.d(TAG, "HUD direction update: " + maneuver + " => " + angle + ", type=" + type);
                }
                mainActivity.mHud.setDirection(angle, type, roundaboutOut);
            }
        };

        IntentFilter filter = new IntentFilter("sky4s.garminhud.app.YANDEX_NAVI_UPDATE");
        ContextCompat.registerReceiver(mainActivity, yandexReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    public void unregisterReceiver() {
        if (yandexReceiver != null) {
            mainActivity.unregisterReceiver(yandexReceiver);
        }
    }
}
