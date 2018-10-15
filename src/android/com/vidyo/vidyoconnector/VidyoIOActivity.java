package com.vidyo.vidyoconnector;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;
import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ToggleButton;
import com.vidyo.VidyoClient.Connector.VidyoConnector;
import com.vidyo.VidyoClient.Connector.Connector;
import com.vidyo.VidyoClient.Device.VidyoDevice;
import com.vidyo.VidyoClient.Device.VidyoLocalCamera;
import com.vidyo.VidyoClient.Endpoint.VidyoLogRecord;

import static android.content.ContentValues.TAG;

public class VidyoIOActivity extends Activity implements VidyoConnector.IConnect,
        VidyoConnector.IRegisterLogEventListener, VidyoConnector.IRegisterLocalCameraEventListener {

    enum VIDYO_CONNECTOR_STATE {
        VC_CONNECTED, VC_DISCONNECTED, VC_DISCONNECTED_UNEXPECTED, VC_CONNECTION_FAILURE
    }

    private VIDYO_CONNECTOR_STATE mVidyoConnectorState = VIDYO_CONNECTOR_STATE.VC_DISCONNECTED;
    private boolean mVidyoConnectorConstructed = false;
    private boolean mVidyoClientInitialized = false;
    private Logger mLogger = Logger.getInstance();
    private VidyoConnector mVidyoConnector = null;
    private ToggleButton mToggleConnectButton;
    private LinearLayout mToolbarLayout;
    private String mHost;
    private String mDisplayName;
    private String mToken;
    private String mResourceId;
    private TextView mToolbarStatus;
    private FrameLayout mVideoFrame;
    private FrameLayout mToggleToolbarFrame;
    private boolean mAutoJoin = false;
    private boolean mAllowReconnect = true;
    private String mReturnURL = null;
    private VidyoIOActivity mSelf;

    /*
     * Operating System Events
     */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mLogger.Log("onCreate");
        super.onCreate(savedInstanceState);
        int activityMainId = getApplicationContext().getResources().getIdentifier("activity_main", "layout",
                getApplicationContext().getPackageName());
        int toggleConnectButtonId = getApplicationContext().getResources().getIdentifier("toggleConnectButton", "id",
                getApplicationContext().getPackageName());
        int toolbarLayoutId = getApplicationContext().getResources().getIdentifier("toolbarLayout", "id",
                getApplicationContext().getPackageName());
        int videoFrameId = getApplicationContext().getResources().getIdentifier("videoFrame", "id",
                getApplicationContext().getPackageName());
        int toggleToolbarFrameId = getApplicationContext().getResources().getIdentifier("toggleToolbarFrame", "id",
                getApplicationContext().getPackageName());
        int toolbarStatusId = getApplicationContext().getResources().getIdentifier("toolbarStatusText", "id",
                getApplicationContext().getPackageName());
        setContentView(activityMainId);
        // Initialize the member variables
        mToggleConnectButton = (ToggleButton) findViewById(toggleConnectButtonId);
        mToolbarLayout = (LinearLayout) findViewById(toolbarLayoutId);
        mVideoFrame = (FrameLayout) findViewById(videoFrameId);
        mToggleToolbarFrame = (FrameLayout) findViewById(toggleToolbarFrameId);
        mToolbarStatus = (TextView) findViewById(toolbarStatusId);
        mSelf = this;

        // Suppress keyboard
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        // Initialize the VidyoClient
        Connector.SetApplicationUIContext(this);
        mVidyoClientInitialized = Connector.Initialize();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        mLogger.Log("onNewIntent");
        super.onNewIntent(intent);

        // New intent was received so set it to use in onStart()
        setIntent(intent);
    }

    @Override
    protected void onStart() {
        this.isStoragePermissionGranted();
        mLogger.Log("onStart");
        super.onStart();

        // If the app was launched by a different app, then get any parameters;
        // otherwise use default settings
        Intent intent = getIntent();
        mHost = intent.hasExtra("host") ? intent.getStringExtra("host") : "prod.vidyo.io";
        mToken = intent.hasExtra("token") ? intent.getStringExtra("token") : null;
        mDisplayName = intent.hasExtra("displayName") ? intent.getStringExtra("displayName") : null;
        mResourceId = intent.hasExtra("resourceId") ? intent.getStringExtra("resourceId") : null;
        mReturnURL = intent.hasExtra("returnURL") ? intent.getStringExtra("returnURL") : null;
        mAutoJoin = intent.getBooleanExtra("autoJoin", false);
        mAllowReconnect = intent.getBooleanExtra("allowReconnect", true);
        mLogger.Log("autoJoin = " + mAutoJoin + ", allowReconnect = " + mAllowReconnect + ", returnUrl =" + mReturnURL);

        // Enable toggle connect button
        mToggleConnectButton.setEnabled(true);
    }

    public void OnLocalCameraAdded(VidyoLocalCamera localCamera) { /* New camera is available */

    }

    public void OnLocalCameraRemoved(VidyoLocalCamera localCamera) {
        /* Existing camera became unavailable */ }

    public void OnLocalCameraSelected(VidyoLocalCamera localCamera) {

        /* Camera was selected by user or automatically */ }

    public void OnLocalCameraStateUpdated(VidyoLocalCamera localCamera, VidyoDevice.VidyoDeviceState state) {
        /* Camera state was updated */ }

    @Override
    protected void onResume() {
        mLogger.Log("onResume");
        super.onResume();

        ViewTreeObserver viewTreeObserver = mVideoFrame.getViewTreeObserver();
        if (viewTreeObserver.isAlive()) {
            viewTreeObserver.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    mVideoFrame.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                    // If the vidyo connector was not previously successfully constructed then
                    // construct it

                    if (!mVidyoConnectorConstructed) {

                        if (mVidyoClientInitialized) {

                            mVidyoConnector = new VidyoConnector(mVideoFrame,
                                    VidyoConnector.VidyoConnectorViewStyle.VIDYO_CONNECTORVIEWSTYLE_Default, 16,
                                    "debug@VidyoClient info@VidyoConnector warning ",
                                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                            + "/VidyoIOAndroid.log",
                                    0);
                            mLogger.Log("Version is " + mVidyoConnector.GetVersion());
                            mVidyoConnector.EnableDebug(0, "");
                            if (mVidyoConnector != null) {
                                mVidyoConnectorConstructed = true;

                                // Set initial position
                                RefreshUI();

                                // Register for log callbacks
                                if (!mVidyoConnector.RegisterLogEventListener(mSelf,
                                        "info@VidyoClient info@VidyoConnector warning")) {
                                    mLogger.Log("VidyoConnector RegisterLogEventListener failed");
                                }
                                if (!mVidyoConnector.RegisterLocalCameraEventListener(mSelf)) {
                                    mLogger.Log("VidyoConnector RegisterLocalCameraEventListener failed");
                                }
                            } else {
                                mLogger.Log("VidyoConnector Construction failed - cannot connect...");
                            }
                        } else {
                            mLogger.Log("ERROR: VidyoClientInitialize failed - not constructing VidyoConnector ...");
                        }

                        Logger.getInstance().Log("onResume: mVidyoConnectorConstructed => "
                                + (mVidyoConnectorConstructed ? "success" : "failed"));
                    }

                    // If configured to auto-join, then simulate a click of the toggle connect
                    // button
                    if (mVidyoConnectorConstructed && mAutoJoin) {
                        mToggleConnectButton.performClick();
                    }
                }
            });
        }
    }

    @Override
    protected void onPause() {
        mLogger.Log("onPause");
        super.onPause();
    }

    @Override
    protected void onRestart() {
        mLogger.Log("onRestart");
        super.onRestart();
        mVidyoConnector.SetMode(VidyoConnector.VidyoConnectorMode.VIDYO_CONNECTORMODE_Foreground);

    }

    @Override
    protected void onStop() {
        mLogger.Log("onStop");
        if (mVidyoConnector != null)
            mVidyoConnector.SetMode(VidyoConnector.VidyoConnectorMode.VIDYO_CONNECTORMODE_Background);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mLogger.Log("onDestroy");
        Connector.Uninitialize();
        super.onDestroy();
    }

    // The device interface orientation has changed
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        mLogger.Log("onConfigurationChanged");
        super.onConfigurationChanged(newConfig);

        // Refresh the video size after it is painted
        ViewTreeObserver viewTreeObserver = mVideoFrame.getViewTreeObserver();
        if (viewTreeObserver.isAlive()) {
            viewTreeObserver.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    mVideoFrame.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                    // Width/height values of views not updated at this point so need to wait
                    // before refreshing UI

                    RefreshUI();
                }
            });
        }
    }
    
    /*
     * Private Utility Functions
     */

    // Refresh the UI
    private void RefreshUI() {
        // Refresh the rendering of the video
        mVidyoConnector.ShowViewAt(mVideoFrame, 0, 0, mVideoFrame.getWidth(), mVideoFrame.getHeight());
        mLogger.Log("VidyoConnectorShowViewAt: x = 0, y = 0, w = " + mVideoFrame.getWidth() + ", h = "
                + mVideoFrame.getHeight());
    }

    // The state of the VidyoConnector connection changed, reconfigure the UI.
    // If connected, dismiss the controls layout
    private void ConnectorStateUpdated(VIDYO_CONNECTOR_STATE state, final String statusText) {
        mLogger.Log("ConnectorStateUpdated, state = " + state.toString());

        mVidyoConnectorState = state;

        // Execute this code on the main thread since it is updating the UI layout

        runOnUiThread(new Runnable() {
            @Override
            public void run() {

                // Update the toggle connect button to either start call or end call image
                mToggleConnectButton.setChecked(mVidyoConnectorState == VIDYO_CONNECTOR_STATE.VC_CONNECTED);

                // Set the status text in the toolbar
                mToolbarStatus.setText(statusText);

                if (mVidyoConnectorState == VIDYO_CONNECTOR_STATE.VC_CONNECTED) {
                    // Enable the toggle toolbar control
                    mToggleToolbarFrame.setVisibility(View.VISIBLE);
                } else {
                    // VidyoConnector is disconnected

                    // Disable the toggle toolbar control
                    mToggleToolbarFrame.setVisibility(View.GONE);

                    // If a return URL was provided as an input parameter, then return to that
                    // application
                    if (mReturnURL != null) {
                        // Provide a callstate of either 0 or 1, depending on whether the call was
                        // successful
                        Intent returnApp = getPackageManager().getLaunchIntentForPackage(mReturnURL);
                        returnApp.putExtra("callstate",
                                (mVidyoConnectorState == VIDYO_CONNECTOR_STATE.VC_DISCONNECTED) ? 1 : 0);
                        startActivity(returnApp);
                    }

                    // If the allow-reconnect flag is set to false and a normal (non-failure)
                    // disconnect occurred,
                    // then disable the toggle connect button, in order to prevent reconnection.
                    if (!mAllowReconnect && (mVidyoConnectorState == VIDYO_CONNECTOR_STATE.VC_DISCONNECTED)) {
                        mToggleConnectButton.setEnabled(false);
                        mToolbarStatus.setText("Call ended");
                    }

                }

            }
        });
    }

    /*
     * Button Event Callbacks
     */

    // The Connect button was pressed.
    // If not in a call, attempt to connect to the backend service.
    // If in a call, disconnect.
    public void ToggleConnectButtonPressed(View v) {
        if (mToggleConnectButton.isChecked()) {
            mToolbarStatus.setText("Connecting...");

            final boolean status = mVidyoConnector.Connect(mHost.toString(), mToken.toString(), mDisplayName.toString(),
                    mResourceId.toString(), this);
            if (!status) {
                ConnectorStateUpdated(VIDYO_CONNECTOR_STATE.VC_CONNECTION_FAILURE, "Connection failed");
            }
            mLogger.Log("VidyoConnectorConnect status = " + status);
        } else {
            // The button just switched to the callStart image: The user is either connected
            // to a resource
            // or is in the process of connecting to a resource; call
            // VidyoConnectorDisconnect to either disconnect
            // or abort the connection attempt.
            // Change the button back to the callEnd image because do not want to assume
            // that the Disconnect
            // call will actually end the call. Need to wait for the callback to be received
            // before swapping to the callStart image.
            mToggleConnectButton.setChecked(true);

            mToolbarStatus.setText("Disconnecting...");

            mVidyoConnector.Disconnect();
        }
    }

    // Toggle the microphone privacy
    public void MicrophonePrivacyButtonPressed(View v) {
        mVidyoConnector.SetMicrophonePrivacy(((ToggleButton) v).isChecked());
    }

    // Toggle the camera privacy
    public void CameraPrivacyButtonPressed(View v) {
        mVidyoConnector.SetCameraPrivacy(((ToggleButton) v).isChecked());
    }

    // Handle the camera swap button being pressed. Cycle the camera.
    public void CameraSwapButtonPressed(View v) {
        mVidyoConnector.CycleCamera();
    }

    // Toggle visibility of the toolbar
    public void ToggleToolbarVisibility(View v) {
        if (mVidyoConnectorState == VIDYO_CONNECTOR_STATE.VC_CONNECTED) {
            if (mToolbarLayout.getVisibility() == View.VISIBLE) {
                mToolbarLayout.setVisibility(View.INVISIBLE);
            } else {
                mToolbarLayout.setVisibility(View.VISIBLE);
            }
        }
    }

    /*
     * Connector Events
     */

    // Handle successful connection.
    public void OnSuccess() {
        mLogger.Log("OnSuccess: successfully connected.");
        ConnectorStateUpdated(VIDYO_CONNECTOR_STATE.VC_CONNECTED, "Connected");
    }

    // Handle attempted connection failure.
    public void OnFailure(VidyoConnector.VidyoConnectorFailReason reason) {
        mLogger.Log("OnFailure: connection attempt failed, reason = " + reason.toString());

        // Update UI to reflect connection failed
        ConnectorStateUpdated(VIDYO_CONNECTOR_STATE.VC_CONNECTION_FAILURE, "Connection failed");
    }

    // Handle an existing session being disconnected.
    public void OnDisconnected(VidyoConnector.VidyoConnectorDisconnectReason reason) {
        if (reason == VidyoConnector.VidyoConnectorDisconnectReason.VIDYO_CONNECTORDISCONNECTREASON_Disconnected) {
            mLogger.Log("OnDisconnected: successfully disconnected, reason = " + reason.toString());
            ConnectorStateUpdated(VIDYO_CONNECTOR_STATE.VC_DISCONNECTED, "Disconnected");
        } else {
            mLogger.Log("OnDisconnected: unexpected disconnection, reason = " + reason.toString());
            ConnectorStateUpdated(VIDYO_CONNECTOR_STATE.VC_DISCONNECTED_UNEXPECTED, "Unexpected disconnection");
        }
    }

    // Handle a message being logged.
    public void OnLog(VidyoLogRecord logRecord) {
        mLogger.LogClientLib(logRecord.message);
    }

    public boolean isStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                Log.v(TAG, "Permission is granted");
                return true;
            } else {

                Log.v(TAG,"Permission is revoked");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA, Manifest.permission_group.MICROPHONE}, 1);
                return false;
            }
        } else { // permission is automatically granted on sdk<23 upon installation
            Log.v(TAG, "Permission is granted");
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.v(TAG, "Permission: " + permissions[0] + "was " + grantResults[0]);
            // resume tasks needing this permission
        }
    }
}

