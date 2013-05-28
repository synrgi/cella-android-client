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

import android.app.Activity;
import android.app.ListActivity;
import android.bluetooth.BluetoothDevice;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.TextView;
import android.widget.ToggleButton;
import edu.washington.cs.cellasecure.bluetooth.BluetoothUtility;
import edu.washington.cs.cellasecure.storage.DeviceUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DriveListActivity extends ListActivity {

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.activity_drive_list);

        setProgressBarIndeterminateVisibility(true);
        // TODO: make actionbar refesh button start spinning

        ListAdapter adapter = new DriveListAdapter();

        setListAdapter(adapter);
    }

    private class DriveListAdapter extends BaseAdapter implements ListAdapter, BluetoothUtility.OnDiscoveryListener, BluetoothUtility.OnDiscoveryFinishedListener {


        private static final int VIEW_TYPE_PAIRED_INRANGE = 0;
        private static final int VIEW_TYPE_INRANGE = 1;
        private static final int VIEW_TYPE_PAIRED_OORANGE = 2;
        private static final int VIEW_TYPE_COUNT = 3;

        // load paired devices and add to list
        // then, scan over bluetooth and add pairable devices
        //
        // [In range + paired] > [Pairable] > [Out of range + paired]
        // In range + paired: text is black, in an "enabled" state
        //                    lock status is shown
        // Pairable:          text is black, in an "enabled" state
        //                    "+" icon is shown instead of lock status
        // Out of range + paired: text is grayed out, in a "disabled" state


        private Activity mActivity = DriveListActivity.this;
        private Set<Drive> mPairedInRangeDrives = new HashSet<Drive>();
        private Set<Drive> mInRangeDrives = new HashSet<Drive>();
        private Set<Drive> mPairedOutOfRangeDrives = new HashSet<Drive>();

        public DriveListAdapter() {
            new PairedDrivesLoadTask().execute();
        }

        @Override
        public int getCount() {
            return mPairedInRangeDrives.size() + mInRangeDrives.size() + mPairedOutOfRangeDrives.size();
        }

        @Override
        public Object getItem(int position) {
            int threshold1 = mPairedInRangeDrives.size();
            int threshold2 = threshold1 + mInRangeDrives.size();
            if (position >= threshold2)
                return mPairedOutOfRangeDrives.toArray()[position - threshold2];
            else if (position >= threshold1)
                return mInRangeDrives.toArray()[position - threshold1];
            else
                return mPairedInRangeDrives.toArray()[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : View.inflate(parent.getContext(), R.layout.list_drive, parent);
            Drive d = (Drive) getItem(position);
            int viewType = getItemViewType(position);

            switch (viewType) {
                case VIEW_TYPE_PAIRED_INRANGE:
                    ((TextView) v.findViewById(R.id.list_drive_name)).setText(d.getName());
                    ((TextView) v.findViewById(R.id.list_drive_address)).setText(d.getAddress());
                    // FIXME: d.isLocked is unimplemented
                    ((ToggleButton) v.findViewById(R.id.list_drive_lock_toggle)).setChecked(d.isLocked());
                    break;
                case VIEW_TYPE_INRANGE:
                    ((TextView) v.findViewById(R.id.list_drive_name)).setText(d.getName());
                    ((TextView) v.findViewById(R.id.list_drive_address)).setText(d.getAddress());
                    // TODO: replace ToggleButton with "+" new device button
                    ((ToggleButton) v.findViewById(R.id.list_drive_lock_toggle)).setChecked(false);
                    break;
                case VIEW_TYPE_PAIRED_OORANGE:
                    TextView tvDriveName = (TextView) v.findViewById(R.id.list_drive_name);
                    tvDriveName.setTextColor(Color.LTGRAY);
                    tvDriveName.setText(d.getName());
                    TextView tvDriveAddress = (TextView) v.findViewById(R.id.list_drive_address);
                    tvDriveAddress.setTextColor(Color.LTGRAY);
                    tvDriveAddress.setText(d.getAddress());
                    // TODO: do something with lock status
                    v.findViewById(R.id.list_drive_lock_toggle).setEnabled(false);
                    break;
            }
            return v;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        @Override
        public boolean isEmpty() {
            return mPairedInRangeDrives.isEmpty() && mInRangeDrives.isEmpty() && mPairedOutOfRangeDrives.isEmpty();
        }

        @Override
        public int getItemViewType(int position) {
            int threshold1 = mPairedInRangeDrives.size();
            int threshold2 = threshold1 + mInRangeDrives.size();
            if (position >= threshold2)
                return VIEW_TYPE_PAIRED_OORANGE;
            else if (position >= threshold1)
                return VIEW_TYPE_INRANGE;
            else
                return VIEW_TYPE_PAIRED_INRANGE;
        }

        @Override
        public int getViewTypeCount() {
            return VIEW_TYPE_COUNT;
        }

        @Override
        public void onDiscovery(List<BluetoothDevice> bluetoothDevices) {
            for (BluetoothDevice dev : bluetoothDevices) {
                boolean added = false;
                for (Drive drive : mPairedOutOfRangeDrives) {
                    if (drive.getDevice().equals(dev)) {
                        mPairedOutOfRangeDrives.remove(drive);
                        mPairedInRangeDrives.add(drive);
                        added = true;
                        break;
                    }
                }
                if (!added)
                    mInRangeDrives.add(new Drive(dev.getName(), dev));
            }

            notifyDataSetChanged();
        }

        @Override
        public void onDiscoveryFinished() {
            // TODO: Make refresh button stop spinning
        }

        private class PairedDrivesLoadTask extends AsyncTask<Void, Void, Map<String, String>> {

            private DriveListAdapter mAdapter = DriveListAdapter.this;

            @Override
            protected Map<String, String> doInBackground(Void... args) {
                return DeviceUtils.fileToMap(mActivity);
            }

            @Override
            protected void onPostExecute(Map<String, String> pairedDevices) {
                for (Map.Entry<String, String> e : pairedDevices.entrySet())
                    mPairedOutOfRangeDrives.add(new Drive(e.getValue(), e.getKey()));
                mAdapter.notifyDataSetChanged();
                BluetoothUtility bt = new BluetoothUtility(mActivity);
                bt.setOnDiscoveryListener(mAdapter);
                bt.setOnDiscoveryFinishedListener(mAdapter);
                bt.scanForDevices();
                mActivity.setProgressBarIndeterminateVisibility(false);
            }
        }
    }

}