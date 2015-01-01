/*
 * StatsCollector - Copyright(c) 2014 Joe Pasqua
 * Provided under the MIT License. See the LICENSE file for details.
 * Created: Nov 30, 2014
 */
package org.noroomattheinn.visibletesla.data;

import com.google.common.collect.Range;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.NavigableMap;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Dialogs;
import javafx.stage.FileChooser;
import org.noroomattheinn.tesla.ChargeState;
import org.noroomattheinn.tesla.StreamState;
import org.noroomattheinn.timeseries.CachedTimeSeries;
import org.noroomattheinn.timeseries.IndexedTimeSeries;
import org.noroomattheinn.timeseries.Row;
import org.noroomattheinn.timeseries.RowDescriptor;
import org.noroomattheinn.timeseries.TimeSeries;
import org.noroomattheinn.utils.GeoUtils;
import org.noroomattheinn.visibletesla.dialogs.DateRangeDialog;
import org.noroomattheinn.visibletesla.fxextensions.TrackedObject;
import org.noroomattheinn.utils.Utils;
import org.noroomattheinn.visibletesla.App;
import org.noroomattheinn.visibletesla.Prefs;
import org.noroomattheinn.visibletesla.ThreadManager;
import org.noroomattheinn.visibletesla.VTVehicle;

/**
 * StatsCollector: Collect stats as they are generated, store them in
 * a TimeSeries, and allow queries against the data.
 *
 * @author Joe Pasqua <joe at NoRoomAtTheInn dot org>
 */
public class StatsCollector implements ThreadManager.Stoppable {
/*------------------------------------------------------------------------------
 *
 * Constants and Enums
 * 
 *----------------------------------------------------------------------------*/
    public static String LastExportDirKey = "APP_LAST_EXPORT_DIR";
    private static final long TenMinutes = 10 * 60 * 1000;
    
    // Data that comes from the ChargeState
    public static final String VoltageKey =     "C_VLT";
    public static final String CurrentKey =     "C_AMP";
    public static final String EstRangeKey =    "C_EST";
    public static final String SOCKey =         "C_SOC";
    public static final String ROCKey =         "C_ROC";
    public static final String BatteryAmpsKey = "C_BAM";
    
    // Data that comes from the StreamState
    public static final String LatitudeKey =    "L_LAT";
    public static final String LongitudeKey =   "L_LNG";
    public static final String HeadingKey =     "L_HDG";
    public static final String SpeedKey =       "L_SPD";
    public static final String OdometerKey =    "L_ODO";
    public static final String PowerKey =       "L_PWR";
    
    public static final String[] Columns = {
        VoltageKey, CurrentKey, EstRangeKey, SOCKey, ROCKey, BatteryAmpsKey,
        LatitudeKey, LongitudeKey, HeadingKey, SpeedKey, OdometerKey, PowerKey};
    public static final RowDescriptor schema = new RowDescriptor(Columns);

/*------------------------------------------------------------------------------
 *
 * Internal State
 * 
 *----------------------------------------------------------------------------*/

    private final App ac;
    private final CachedTimeSeries ts;
            
/*==============================================================================
 * -------                                                               -------
 * -------              Public Interface To This Class                   ------- 
 * -------                                                               -------
 *============================================================================*/
    
    /** 
     * The last StreamState that was persisted
     */
    public final TrackedObject<StreamState> lastStoredStreamState;
    
    /** 
     * The last ChargeState that was persisted
     */
    public final TrackedObject<ChargeState> lastStoredChargeState;

    /**
     * Create a new StatsCollector that will monitor new states being generated
     * by the StatsStreamer and persist them as appropriate. Not every state will
     * be persisted and not every value from each state is persisted. A state may
     * not be persisted if a previous state was persisted too "recently". This
     * constructor might result in upgrading the underlying repository if its
     * format has changed.
     * 
     * @param appContext    Describes where app files are to be stored and provides
     *                      other contextual information.
     * @throws IOException  If the underlying persistent store has a problem.
     */
    public StatsCollector()
            throws IOException {
        this.ac = App.get();

        upgradeIfNeeded(ac.appFileFolder(), VTVehicle.get().getVehicle().getVIN());
        
        this.ts = new CachedTimeSeries(
                ac.appFileFolder(), VTVehicle.get().getVehicle().getVIN(),
                schema, Prefs.get().getLoadPeriod());

        this.lastStoredStreamState = new TrackedObject<>(new StreamState());
        this.lastStoredChargeState = new TrackedObject<>(null);
        
        VTVehicle.get().streamState.addListener(new ChangeListener<StreamState>() {
            @Override public void changed(
                    ObservableValue<? extends StreamState> ov,
                    StreamState old, StreamState cur) {
                handleStreamState(cur);
            }
        });
        
        VTVehicle.get().chargeState.addListener(new ChangeListener<ChargeState>() {
            @Override public void changed(
                    ObservableValue<? extends ChargeState> ov,
                    ChargeState old, ChargeState cur) {
                handleChargeState(cur);
            }
        });
        
        ThreadManager.get().addStoppable((ThreadManager.Stoppable)this);
    }
    
    /**
     * Create a Row based on the Charge and Stream States provided. The timestamp
     * if based on the timestamp of the newest state object
     * @param cs    The ChargeState from which various column values will be pulled
     * @param ss    The StreamState from which various column values will be pulled
     * @return      The newly created and initialized Row
     */
    public Row rowFromStates(ChargeState cs, StreamState ss) {
        Row r = new Row(Math.max(cs.timestamp, ss.timestamp), 0L, schema.nColumns);
        
        r.set(schema, VoltageKey, cs.chargerVoltage);
        r.set(schema, CurrentKey, cs.chargerActualCurrent);
        r.set(schema, EstRangeKey, cs.range);
        r.set(schema, SOCKey, cs.batteryPercent);
        r.set(schema, ROCKey, cs.chargeRate);
        r.set(schema, BatteryAmpsKey, cs.batteryCurrent);
        r.set(schema, LatitudeKey, ss.estLat);
        r.set(schema, LongitudeKey, ss.estLng);
        r.set(schema, HeadingKey, ss.heading);
        r.set(schema, SpeedKey, ss.speed);
        r.set(schema, OdometerKey, ss.odometer);
        r.set(schema, PowerKey, ss.power);
        
        return r;
    }
    
    /**
     * Return a TimeSeries for all of the collected data. 
     * @return The TimeSeries
     */
    public TimeSeries getFullTimeSeries() { return ts; }
    
    /**
     * Return an IndexedTimeSeries for only the data loaded into memory. The
     * range of data loaded is controlled by a preference.
     * @return The IndexedTimeSeries
     */
    public IndexedTimeSeries getLoadedTimeSeries() { return ts.getCachedSeries(); }
    
    /**
     * Return an index on a set of rows covered by the period [startTime..endTime].
     * 
     * @param startTime Starting time for the period
     * @param endTime   Ending time for the period
     * @return A map from time -> Row for all rows in the time range
     */
    public NavigableMap<Long,Row> getRangeOfLoadedRows(long startTime, long endTime) {
        return getLoadedTimeSeries().getIndex(Range.open(startTime, endTime));
    }
    
    /**
     * Return an index on the cached rows in the data store.
     *
     * @return A map from time -> Row for all rows in the store
     */
    public NavigableMap<Long,Row> getAllLoadedRows() {
        return getLoadedTimeSeries().getIndex();
    }
    
    /**
     * Export selected columns to an Excel file
     * @param columns   A list of the columns to export. The columns must 
     *                  correspond to entries in StatsCollector.Columns
     */
    public void export(String[] columns) {
        String initialDir = Prefs.store().get(
                LastExportDirKey, System.getProperty("user.home"));
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Data");
        fileChooser.setInitialDirectory(new File(initialDir));

        File file = fileChooser.showSaveDialog(ac.stage);
        if (file != null) {
            String enclosingDirectory = file.getParent();
            if (enclosingDirectory != null)
                Prefs.store().put(LastExportDirKey, enclosingDirectory);
            Range<Long> exportPeriod = DateRangeDialog.getExportPeriod(ac.stage);
            if (exportPeriod == null)
                return;
            if (ts.export(file, exportPeriod, Arrays.asList(columns), true)) {
                Dialogs.showInformationDialog(
                    ac.stage, "Your data has been exported",
                    "Data Export Process" , "Export Complete");
            } else {
                Dialogs.showErrorDialog(
                    ac.stage, "Unable to save to: " + file,
                    "Data Export Process" , "Export Failed");
            }
        }
    }
    
    /**
     * Shut down the StatsCollector.
     */
    @Override public void stop() { ts.close(); }

/*------------------------------------------------------------------------------
 *
 * PRIVATE - Methods related to storing new samples
 * 
 *----------------------------------------------------------------------------*/
    
    private synchronized void handleChargeState(ChargeState state) {
        Row r = new Row(state.timestamp, 0L, schema.nColumns);
        
        r.set(schema, VoltageKey, state.chargerVoltage);
        r.set(schema, CurrentKey, state.chargerActualCurrent);
        r.set(schema, EstRangeKey, state.range);
        r.set(schema, SOCKey, state.batteryPercent);
        r.set(schema, ROCKey, state.chargeRate);
        r.set(schema, BatteryAmpsKey, state.batteryCurrent);
        ts.storeRow(r);
        
        lastStoredChargeState.set(state);
    }
    
    private synchronized void handleStreamState(StreamState state) {
        StreamState lastRecorded = lastStoredStreamState.get();
        if (worthRecording(state, lastRecorded)) {
            Row r = new Row(state.timestamp, 0L, schema.nColumns);

            r.set(schema, LatitudeKey, state.estLat);
            r.set(schema, LongitudeKey, state.estLng);
            r.set(schema, HeadingKey, state.heading);
            r.set(schema, SpeedKey, Utils.round(state.speed, 1));
            r.set(schema, OdometerKey, state.odometer);
            r.set(schema, PowerKey, state.power);
            ts.storeRow(r);

            if (state.odometer - lastStoredStreamState.get().odometer >= 1.0) {
                Prefs.store().putDouble(ac.vinKey("odometer"), state.odometer);
            }
            lastStoredStreamState.set(state);
        }
    }
    
    private boolean worthRecording(StreamState cur, StreamState last) {
        double meters = GeoUtils.distance(cur.estLat, cur.estLng, last.estLat, last.estLng);
        
        // The app becoming active makes it worth recording
        if (ac.isActive() && ac.state.lastSet() > last.timestamp) return true;
        
        // A big turn makes it worth recording. Note that heading changes can be
        // spurious. They can happen when the car is sitting still. Ignore those.
        double turn =  180.0 - Math.abs((Math.abs(cur.heading - last.heading)%360.0) - 180.0);
        if (turn > 10 && moving(cur)) return true; 
        
        // A long time between readings makes it worth recording
        long timeDelta = Math.abs(cur.timestamp - last.timestamp);
        if (timeDelta > TenMinutes) { return true; }
        
        // A change in motion (moving->stationary or stationaty->moving) is worth recording
        if (moving(last) != moving(cur)) { return true; }
        
        // If you're moving and it's been a while since a reading, it's worth recording
        if ((timeDelta >= Prefs.get().locMinTime.get() * 1000) &&
            (meters >= Prefs.get().locMinDist.get())) return true;
        
        return false;
    }

    private boolean moving(StreamState state) { return state.speed > 0.1; }
    
    private boolean upgradeIfNeeded(File container, String baseName) throws IOException {
        DBConverter converter = new DBConverter(container, baseName);
        if (!converter.conversionRequired()) return false;
        Dialogs.showInformationDialog(
            ac.stage,
            "Your data files must be upgraded\nPress OK to begin the process.",
            "Data Upgrade Process" , "Data File Upgrade");
        converter.convert();
        Dialogs.showInformationDialog(
            ac.stage,
            "Your data files have been upgraded\nPress OK to continue.",
            "Data Upgrade Process" , "Process Complete");
        return true;
    }
}
