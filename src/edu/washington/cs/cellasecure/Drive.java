/*
 * Copyright 2013 CellaSecure
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package edu.washington.cs.cellasecure;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import edu.washington.cs.cellasecure.bluetooth.Connection;
import edu.washington.cs.cellasecure.bluetooth.Connection.OnResponseListener;
import edu.washington.cs.cellasecure.bluetooth.DeviceConfiguration;

public class Drive implements Parcelable {

    private static final String TAG = "Drive";

    // BEGIN PARCELABLE ///////////////////////////////////////////////////////

    public static final String KEY_BUNDLE_DRIVE = "drive";

    public static final Parcelable.Creator<Drive> CREATOR = new Parcelable.Creator<Drive>() {
        public Drive createFromParcel(Parcel in) {
            return new Drive(in);
        }

        public Drive[] newArray(int size) {
            return new Drive[size];
        }
    };

    private Drive(Parcel in) {
        mName   = in.readString();
        mDevice = in.readParcelable(null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeParcelable(mDevice, flags);
    }

    // END PARCELABLE /////////////////////////////////////////////////////////

    private static final String BT_DEV_DEFAULT_NAME = "Unnamed";

    private String          mName;
    private BluetoothDevice mDevice;
    private Connection      mConnection;

    private OnConfigurationListener   mOnConfigurationListener;
    private OnConnectListener         mOnConnectListener;
    private OnFactoryResetListener    mOnFactoryResetListener;
    private OnLockQueryResultListener mOnLockQueryResultListener;
    private OnLockStateChangeListener mOnLockStateChangeListener;
    private OnPasswordSetListener     mOnPasswordSetListener;

    public Drive(BluetoothDevice bt) {
        this(bt.getName(), bt);
    }


    public Drive(String name, String address) {
        this(name, BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address));
    }

    public Drive(String name, BluetoothDevice bt) {
        mName   = (name == null || name.isEmpty()) ? BT_DEV_DEFAULT_NAME : name;
        mDevice = bt;
    }

    public String getAddress() {
        return mDevice.getAddress();
    }

    public String getName() {
        return mName;
    }

    public BluetoothDevice getDevice() {
        return mDevice;
    }

    // BEGIN BLUETOOTH ////////////////////////////////////////////////////////

    public static final boolean STATUS_LOCKED = true;
    public static final boolean STATUS_UNLOCKED = false;

    public static final byte RESPONSE_OKAY_BYTE = 'K';
    public static final byte RESPONSE_BAD_BYTE = '~';

    private static final byte[] CONFIG_REQUEST_BYTES   = {'g'};
    private static final byte[] FACTORY_RESET_BYTES    = {'r'};
    private static final byte[] LOCK_STATE_QUERY_BYTES = {'?'};
    private static final byte[] LOCK_REQUEST_BYTES     = {'l'};
    private static final byte[] MOUNT_REQUEST_BYTES    = {'k'};
    private static final byte[] UNMOUNT_REQUEST_BYTES  = {'u'};

    private static final byte   CONFIG_SEND_BYTE = 'c';
    private static final byte   PASSWD_SEND_BYTE = 'p';
    private static final byte   PASSWD_SET_BYTE = 'n';

    private static final int LOCK_STATE_QUERY_RESPONSE_SIZE = 2;
    private static final int YES_NO_QUERY_RESPONSE_SIZE = 1;

    private static final int CONFIG_STRING_SIZE = 1;
    private static final int PASSWD_MAX_LENGTH  = 32;
    private static final int UUID_LENGTH        = 16;

    // CONNECTIONS

    public interface OnConnectListener {
        /**
         * Callback to notify a client when the drive is connected
         */
        public void onConnect();

        /**
         * Callback to notify a client when the drive fails to connect
         */
        public void onConnectFailure(IOException connectException);
    }

    public void setOnConnectListener(OnConnectListener listener) {
        mOnConnectListener = listener;
    }

    public void connect() {
        new DriveConnectTask(this, mOnConnectListener).run();
    }

    public boolean isConnected() {
        return mConnection != null && mConnection.isConnected();
    }

    public void disconnect() {
        try {
            mConnection.close();
        } catch (IOException e) {
            Log.e(TAG, "Error disconnecting", e);
        }
    }

    // LOCK QUERY

    public interface OnLockQueryResultListener {
        /**
         * Callback to notify a client whether a device is unlocked or not
         *
         * @param status         true is device is locked, false otherwise
         * @param queryException if there was an error, this was the exception raised
         */
        public void onLockQueryResult(boolean status, IOException queryException);
    }

    public void setOnLockQueryResultListener(OnLockQueryResultListener listener) {
        mOnLockQueryResultListener = listener;
    }

    public void queryLockStatus() {
        mConnection.setOnResponseListener(new OnResponseListener() {
            private static final String TAG = "QueryLockStatus";

            @Override
            public void onResponse(byte[] message, IOException e) {
                if (e != null) {
                    Log.e(TAG, "Send failure", e);
                    if (mOnLockQueryResultListener != null) {
                        mOnLockQueryResultListener.onLockQueryResult(STATUS_LOCKED, e);
                    }
                    return;
                }

                if (message[0] != LOCK_STATE_QUERY_BYTES[0]) {
                    Log.e(TAG, "Invalid response");
                    if (mOnLockQueryResultListener != null) {
                        mOnLockQueryResultListener.onLockQueryResult(STATUS_LOCKED, new IOException(
                                "Invalid response: " + Arrays.toString(message)));
                    }
                }

                if (mOnLockQueryResultListener != null) {
                    mOnLockQueryResultListener.onLockQueryResult(message[1] == 'L', null);
                }
            }
        });
        mConnection.send(LOCK_STATE_QUERY_BYTES, LOCK_STATE_QUERY_RESPONSE_SIZE);
    }

    // LOCK/UNLOCK

    public interface OnLockStateChangeListener {

        /**
         * Callback to notify a client whether an attempt to unlock/lock was successful
         *
         * @param status             true if device is locked, false otherwise
         * @param lockStateException if there was an error, this was the exception raised
         */
        public void onLockStateChanged(boolean status, IOException lockStateException);
    }

    public void setOnLockStateChangeListener(OnLockStateChangeListener listener) {
        mOnLockStateChangeListener = listener;
    }

    public void unlock(String password, final String uuid, int encryptionLevel) {
        if (password.length() > PASSWD_MAX_LENGTH) {
            Log.e(TAG, "Password is too long");
            throw new IllegalArgumentException("Password is too long!");
        }
        mConnection.setOnResponseListener(new OnResponseListener() {
            private static final String TAG = "UnlockListener";

            @Override
            public void onResponse(byte[] message, IOException e) {
                mConnection.setOnResponseListener(new OnResponseListener() {
                    @Override
                    public void onResponse(byte[] message, IOException e) {
                        Log.e(TAG, "Entering onResponse for unlock");
                        if (e != null) {
                            Log.e(TAG, "Unlock request failure", e);
                            if (mOnLockStateChangeListener != null) {
                                mOnLockStateChangeListener.onLockStateChanged(STATUS_LOCKED, e);
                            }
                            return;
                        }

                        boolean success = message[0] == RESPONSE_OKAY_BYTE;
                        if (success && mOnLockStateChangeListener != null) {
                            mConnection.setOnResponseListener(new OnResponseListener() {
                                @Override
                                public void onResponse(byte[] message, IOException e) {
                                    if (e == null && message[0] == 'K')
                                        mOnLockStateChangeListener.onLockStateChanged(STATUS_UNLOCKED, null);
                                    else {
                                        Log.d(TAG, "Failed to mount drive");
                                        if (mOnLockStateChangeListener != null)
                                            mOnLockStateChangeListener.onLockStateChanged(false, new IOException("Validation failed"));
                                    }
                                }
                            });
                            mConnection.send(MOUNT_REQUEST_BYTES, YES_NO_QUERY_RESPONSE_SIZE);
                        } else if (mOnLockStateChangeListener != null) {
                            Log.e(TAG, "Bad password during request");
                            if (mOnLockStateChangeListener != null)
                                mOnLockStateChangeListener.onLockStateChanged(STATUS_LOCKED, new IOException(
                                        "Bad password during request"));
                        }
                    }
                });
                if (e == null && message[0] == RESPONSE_OKAY_BYTE) {
                    byte[] newMessage = new byte[UUID_LENGTH];
                    System.arraycopy(uuid.getBytes(), 0, newMessage, 0, uuid.length());
                    Log.d(TAG, "Sending UUID: " + uuid);
                    mConnection.send(newMessage, YES_NO_QUERY_RESPONSE_SIZE);
                } else {
                    Log.e(TAG, "Failed", e);
                }
            }
        });
        byte[] message;
        if (encryptionLevel == 0)
            message = new byte[] {PASSWD_SEND_BYTE};
        else {
                message = new byte[1 + PASSWD_MAX_LENGTH];
//            } else {
//                message = new byte[1 + PASSWD_MAX_LENGTH + UUID_LENGTH];
//                System.arraycopy(uuid.getBytes(), 0, message, 1 + PASSWD_MAX_LENGTH, uuid.length());
//            }
            message[0] = PASSWD_SEND_BYTE;
            System.arraycopy(password.getBytes(), 0, message, 1, password.length());
        }
        Log.d(TAG, "Sending password");
        Log.d(TAG, "message: " + new String(message));
        mConnection.send(message, YES_NO_QUERY_RESPONSE_SIZE);
    }

    public void lock() {
        mConnection.setOnResponseListener(new OnResponseListener() {
            private static final String TAG = "LockListener";

            @Override
            public void onResponse(byte[] message, IOException e) {
                if (e != null) {
                    Log.e(TAG, "Lock request failure", e);
                    if (mOnLockStateChangeListener != null) {
                        mOnLockStateChangeListener.onLockStateChanged(STATUS_UNLOCKED, e);
                    }
                    return;
                }

                boolean success = (message[0] == RESPONSE_OKAY_BYTE);
                if (success && mOnLockStateChangeListener != null) {
                    mOnLockStateChangeListener.onLockStateChanged(STATUS_LOCKED, null);
                } else if (mOnLockStateChangeListener != null) {
                    mOnLockStateChangeListener.onLockStateChanged(STATUS_UNLOCKED, new IOException(
                            "Bad lock request"));
                }
            }
        });
        mConnection.send(LOCK_REQUEST_BYTES, YES_NO_QUERY_RESPONSE_SIZE);
    }

    // FACTORY RESET

    public interface OnFactoryResetListener {

        /**
         * Callback to notify client that factory reset is complete
         *
         * @param e if there was an error, this exception will be non-null
         */
        public void onFactoryReset(IOException e);
    }

    public void setOnFactoryResetListener(OnFactoryResetListener listener) {
        mOnFactoryResetListener = listener;
    }

    public void factoryReset() {
        mConnection.setOnResponseListener(new OnResponseListener() {
            private static final String TAG = "FactoryResetListener";

            @Override
            public void onResponse(byte[] message, IOException e) {
                if (e != null) {
                    Log.e(TAG, "Factory reset request failure", e);
                    if (mOnFactoryResetListener != null) {
                        mOnFactoryResetListener.onFactoryReset(e);
                    }
                    return;
                }

                if (message[0] == RESPONSE_OKAY_BYTE) {
                    if (mOnFactoryResetListener != null) {
                        mOnFactoryResetListener.onFactoryReset(null);
                    }
                } else {
                    if (mOnFactoryResetListener != null) {
                        mOnFactoryResetListener.onFactoryReset(
                                new IOException("Factory reset failed"));
                    }
                }
            }
        });
        mConnection.send(FACTORY_RESET_BYTES, YES_NO_QUERY_RESPONSE_SIZE);
    }

    // CONFIGURATION

    public interface OnConfigurationListener {
        /**
         * Callback to notify a client when configuration is received
         *
         * @param config the configuration received from Bluetooth device, null if failure
         * @param e      if there is an error, this will be non-null
         */
        public void onConfigurationRead(DeviceConfiguration config, IOException e);

        /**
         * Callback to notify a client when configuration is sent
         *
         * @param e if there is an error, this will be non-null
         */
        public void onConfigurationWritten(IOException e);
    }

    public void setOnConfigurationListener(OnConfigurationListener listener) {
        mOnConfigurationListener = listener;
    }

    public void readConfiguration() {
        mConnection.setOnResponseListener(new OnResponseListener() {
            private static final String TAG = "ConfigReadListener";

            @Override
            public void onResponse(byte[] message, IOException e) {
                if (e != null) {
                    Log.e(TAG, "Error requesting config", e);
                    if (mOnConfigurationListener != null) {
                        mOnConfigurationListener.onConfigurationRead(null, e);
                    }
                    return;
                }
                Log.e(TAG, "Message: " + new String(message));
                if (message.length == CONFIG_STRING_SIZE + 1 && 
                        message[CONFIG_STRING_SIZE] == RESPONSE_OKAY_BYTE) {
                    byte[] config = Arrays.copyOfRange(message, 0, CONFIG_STRING_SIZE);
                    DeviceConfiguration devConfig = new DeviceConfiguration(config);
                    if (mOnConfigurationListener != null) {
                        mOnConfigurationListener.onConfigurationRead(devConfig, null);
                    }
                } else if (mOnConfigurationListener != null) {
                    mOnConfigurationListener.onConfigurationRead(null, new IOException(
                            "Bad config request"));
                }

            }
        });
        mConnection.send(CONFIG_REQUEST_BYTES, CONFIG_STRING_SIZE + 1);
    }

    public void sendConfiguration(final DeviceConfiguration configuration, final String uuid) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        mConnection.setOnResponseListener(new OnResponseListener() {
            @Override 
            public void onResponse(byte[] message, IOException e) {
                if (e != null) {
                    Log.e(TAG, "Error requesting unmount");
                    if (mOnConfigurationListener != null)
                        mOnConfigurationListener.onConfigurationWritten(e);
                    return;
                }
                mConnection.setOnResponseListener(new OnResponseListener() {
                    @Override
                    public void onResponse(byte[] message, IOException e) {
                        mConnection.setOnResponseListener(new OnResponseListener() {
                            
                            @Override
                            public void onResponse(byte[] message, IOException e) {
                                if (e != null) {
                                    Log.e(TAG, "Error requesting config send", e);
                                    if (mOnConfigurationListener != null) {
                                        mOnConfigurationListener.onConfigurationWritten(e);
                                    }
                                    return;
                                }
                                if (mOnConfigurationListener != null) {
                                    if (message[0] != RESPONSE_OKAY_BYTE) {
                                        Log.e(TAG, "response Message: " + new String(message));
                                        mOnConfigurationListener.onConfigurationWritten(
                                                new IOException("Device did not accept config"));
                                    } else {
                                        mConnection.setOnResponseListener(new OnResponseListener() {
                                            @Override
                                            public void onResponse(byte[] message, IOException e) {
                                                if (e != null) {
                                                    Log.e(TAG, "Remount failed");
                                                    if (mOnConfigurationListener != null)
                                                        mOnConfigurationListener.onConfigurationWritten(e);
                                                } else if (message[0] == RESPONSE_OKAY_BYTE) {
                                                    Log.d(TAG, "Configuration set success!");
                                                    mOnConfigurationListener.onConfigurationWritten(null);
                                                } else {
                                                    Log.e(TAG, "Device rejected remounting");
                                                }
                                            }
                                        });
                                    }
                                }
                                mConnection.send(MOUNT_REQUEST_BYTES, YES_NO_QUERY_RESPONSE_SIZE);
                            }
                        });
                        if (message[0] == RESPONSE_OKAY_BYTE) {
                            byte[] newMessage = new byte[UUID_LENGTH];
                            Log.d(TAG, "UUID in setconfig: " + uuid);
                            System.arraycopy(uuid.getBytes(), 0, newMessage, 0, uuid.length());
                            mConnection.send(newMessage, YES_NO_QUERY_RESPONSE_SIZE);
                        } else {
                            Log.e(TAG, "Config send failed");
                        }
                    }
                });
                if (message[0] == RESPONSE_OKAY_BYTE) {
                    byte[] configBytes = configuration.getBytes();
                    byte[] newMessage = new byte[1 + configBytes.length];
                    newMessage[0] = CONFIG_SEND_BYTE;
                    System.arraycopy(configBytes, 0, newMessage, 1, configBytes.length);
                    Log.d(TAG, "newMessage: " + new String(newMessage));
//                    Log.d(TAG, "uuid length: " + uuid.length() + " uuid: " + uuid);
//                    Log.d(TAG, "configByte length is " + configBytes.length);
                    Log.d(TAG, "newMessage length is " + newMessage.length);
                    mConnection.send(newMessage, YES_NO_QUERY_RESPONSE_SIZE);
                } else {
                    Log.e(TAG, "Failed to unmount");
                }
            }
        });
        mConnection.send(UNMOUNT_REQUEST_BYTES, YES_NO_QUERY_RESPONSE_SIZE);
    }

    private static class DriveConnectTask implements Runnable {
        private static final String TAG = "DriveConnectTask";

        private static final UUID BT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

        private final Drive mDrive;
        private final BluetoothSocket mSocket;
        private OnConnectListener mListener;

        public DriveConnectTask(Drive drive, OnConnectListener cl) {
            BluetoothSocket tmp = null;
            try {
                tmp = drive.mDevice.createRfcommSocketToServiceRecord(BT_UUID);
            } catch (IOException socketException) {
                Log.e(TAG, "Failed to create Rfcomm socket", socketException);
            }
            mDrive = drive;
            mSocket = tmp;
            mListener = cl;
        }

        public void run() {
            Connection c;
            try {
                mSocket.connect();
                c = new Connection(mSocket);
            } catch (IOException connectException) {
                Log.e("Foo", "Failed to connect", connectException);
                try {
                    mSocket.close();
                } catch (IOException ignored) { /* pass */ }
                if (mListener != null) {
                    mListener.onConnectFailure(connectException);
                }
                return;
            }
            mDrive.mConnection = c;
            if (mListener != null) {
                mListener.onConnect();
            }
        }
    }

    // SET PASSWORD

    public interface OnPasswordSetListener {

        /**
         * Callback to notify client that the password has been set
         *
         * @param e if not null, an error has occured
         */
        public void onPasswordSet(IOException e);
    }

    public void setOnPasswordSetListener (OnPasswordSetListener psl) {
        mOnPasswordSetListener = psl;
    }
    
    public void setPassword(final String password, final String uuid) {
        if (password.length() > PASSWD_MAX_LENGTH) {
            throw new IllegalArgumentException("Password is too long");
        }
        mConnection.setOnResponseListener(new OnResponseListener() {
            @Override 
            public void onResponse(byte[] message, IOException e) {
                if (e != null) {
                    Log.e(TAG, "Error requesting unmount");
                    if (mOnPasswordSetListener != null)
                        mOnPasswordSetListener.onPasswordSet(e);
                    return;
                }
                mConnection.setOnResponseListener(new OnResponseListener() {
                    @Override
                    public void onResponse(byte[] message, IOException e) {
                        mConnection.setOnResponseListener(new OnResponseListener() {
                            @Override
                            public void onResponse(byte[] message, IOException e) {
                                if (e != null) {
                                    Log.e(TAG, "Error requesting password change", e);
                                    if (mOnPasswordSetListener != null) {
                                        mOnPasswordSetListener.onPasswordSet(e);
                                    }
                                    return;
                                }
                                if (mOnPasswordSetListener != null) {
                                    if (message[0] != RESPONSE_OKAY_BYTE) {
                                        Log.e(TAG, "response Message: " + new String(message));
                                        mOnPasswordSetListener.onPasswordSet(
                                                new IOException("Device did not accept password"));
                                    } else {
                                        mConnection.setOnResponseListener(new OnResponseListener() {
                                            @Override
                                            public void onResponse(byte[] message, IOException e) {
                                                if (e != null) {
                                                    Log.e(TAG, "Remount failed");
                                                    if (mOnPasswordSetListener != null)
                                                        mOnPasswordSetListener.onPasswordSet(e);
                                                } else if (message[0] == RESPONSE_OKAY_BYTE) {
                                                    Log.d(TAG, "Configuration set success!");
                                                    mOnPasswordSetListener.onPasswordSet(null);
                                                } else {
                                                    Log.e(TAG, "Device rejected remounting");
                                                }
                                            }
                                        });
                                    }
                                }
                                mConnection.send(MOUNT_REQUEST_BYTES, YES_NO_QUERY_RESPONSE_SIZE);
                            }
                        });
                        if (message[0] == RESPONSE_OKAY_BYTE) {
                            byte[] newMessage = new byte[UUID_LENGTH];
                            Log.d(TAG, "UUID in setpass: " + uuid);
                            System.arraycopy(uuid.getBytes(), 0, newMessage, 0, uuid.length());
                            mConnection.send(newMessage, YES_NO_QUERY_RESPONSE_SIZE);
                        } else {
                            Log.e(TAG, "Password set failed");
                        }
                    }
                });
                if (message[0] == RESPONSE_OKAY_BYTE) {
                    byte[] newMessage = new byte[1 + PASSWD_MAX_LENGTH];
                    newMessage[0] = PASSWD_SET_BYTE;
                    System.arraycopy(password.getBytes(), 0, newMessage, 1, password.length());
                    Log.d(TAG, "newMessage: " + new String(newMessage));
                    Log.d(TAG, "newMessage length is " + newMessage.length);
                    mConnection.send(newMessage, YES_NO_QUERY_RESPONSE_SIZE);
                } else {
                    Log.e(TAG, "Failed to unmount");
                    if (mOnPasswordSetListener != null) mOnPasswordSetListener.onPasswordSet(
                            new IOException("Failed to mount"));
                }
            }
        });
        mConnection.send(UNMOUNT_REQUEST_BYTES, YES_NO_QUERY_RESPONSE_SIZE);
    }

    // END BLUETOOTH //////////////////////////////////////////////////////////

    @Override
    public String toString() {
        return mName + " " + mDevice.getAddress();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mDevice == null) ? 0 : mDevice.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Drive other = (Drive) obj;
        if (mDevice == null) {
            if (other.mDevice != null) {
                return false;
            }
        } else if (!mDevice.equals(other.mDevice)) {
            return false;
        }
        return true;
    }
}
