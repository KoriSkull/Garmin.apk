package sky4s.garminhud.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.util.Log;

import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/* Fragment used as page 3 */
public class Page3Fragment extends Fragment {

    private TextView yandexDebugText;
    private BroadcastReceiver yandexReceiver;
    private static final String TAG = "Page3Fragment";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_page3, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        ((MainActivity) getActivity()).mBMWHUDEnabledSwitch = getView().findViewById((R.id.switchEnableBMWHUD));
        ((MainActivity) getActivity()).mArrowTypeSwitch = getView().findViewById(R.id.switchArrowType);
        ((MainActivity) getActivity()).mArrowDebugSwitch = getView().findViewById(R.id.switchArrowDebug);

        ((MainActivity) getActivity()).mAlertAnytimeSwitch = getView().findViewById(R.id.switchAlertAnytime);
        SeekBar seekBarAlertSpeed = (SeekBar) getView().findViewById(R.id.seekBarAlertSpeed);
        seekBarAlertSpeed.setEnabled(false);
        ((MainActivity) getActivity()).mAlertSpeedSeekbar = seekBarAlertSpeed;
        ((MainActivity) getActivity()).mAlertYellowTrafficSwitch = getView()
                .findViewById(R.id.switchAlertYellowTraffic);

        ((MainActivity) getActivity()).mBindBtAddressSwitch = getView().findViewById(R.id.switchBtBindAddress);
        ((MainActivity) getActivity()).mShowNotifySwitch = getView().findViewById(R.id.switchShowNotify);

        ((MainActivity) getActivity()).mDarkModeAutoSwitch = getView().findViewById(R.id.switchDarkModeAuto);
        ((MainActivity) getActivity()).mDarkModeManualSwitch = getView().findViewById(R.id.switchDarkModeMan);

        // Initialize debug text view
        yandexDebugText = getView().findViewById(R.id.yandexDebugText);

        getView().findViewById(R.id.btnTestHud).setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), TestHudPlusActivity.class);
            startActivity(intent);
        });

        ((MainActivity) getActivity()).loadOptions();

        setupYandexReceiver();
    }

    private void setupYandexReceiver() {
        yandexReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    String speedLimit = intent.getStringExtra("SPEED_LIMIT");
                    String distance = intent.getStringExtra("DISTANCE");
                    String eta = intent.getStringExtra("ETA");
                    String remainTime = intent.getStringExtra("REMAIN_TIME");
                    String maneuverDesc = intent.getStringExtra("MANEUVER_DESC");
                    boolean isNavigating = intent.getBooleanExtra("IS_NAVIGATING", false);

                    updateDebugText(speedLimit, distance, eta, remainTime, maneuverDesc, isNavigating);
                } catch (Exception e) {
                    Log.e(TAG, "Error processing broadcast", e);
                }
            }
        };

        IntentFilter filter = new IntentFilter("sky4s.garminhud.app.YANDEX_NAVI_UPDATE");
        if (getActivity() != null) {
            ContextCompat.registerReceiver(getActivity(), yandexReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }
    }

    private void updateDebugText(String speedLimit, String distance, String eta,
            String remainTime, String maneuverDesc, boolean isNavigating) {
        if (yandexDebugText == null)
            return;

        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

        StringBuilder sb = new StringBuilder();
        sb.append("=== YANDEX NAVIGATOR DATA ===\n");
        sb.append("Last Update: ").append(timestamp).append("\n");
        sb.append("Navigating: ").append(isNavigating ? "YES" : "NO").append("\n\n");

        sb.append("Speed Limit: ").append(speedLimit != null ? speedLimit : "N/A").append("\n");
        sb.append("Distance: ").append(distance != null ? distance : "N/A").append("\n");
        sb.append("ETA (Arrival): ").append(eta != null ? eta : "N/A").append("\n");
        sb.append("Time Remaining: ").append(remainTime != null ? remainTime : "N/A").append("\n");
        sb.append("Maneuver: ").append(maneuverDesc != null ? maneuverDesc : "N/A").append("\n");

        yandexDebugText.post(() -> yandexDebugText.setText(sb.toString()));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (yandexReceiver != null && getActivity() != null) {
            try {
                getActivity().unregisterReceiver(yandexReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver not registered
            }
        }
    }
}
