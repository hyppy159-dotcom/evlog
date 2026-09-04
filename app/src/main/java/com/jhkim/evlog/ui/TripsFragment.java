package com.jhkim.evlog.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.jhkim.evlog.R;
import com.jhkim.evlog.db.Db;
import com.jhkim.evlog.db.Trip;
import com.jhkim.evlog.logger.LiveState;
import com.jhkim.evlog.util.Fmt;

import java.util.List;

public class TripsFragment extends Fragment {

    private RecyclerView list;
    private TextView empty;
    private TripAdapter adapter;
    private int lastRevision = -1;

    private final Runnable liveListener = this::maybeReload;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle saved) {
        View v = inflater.inflate(R.layout.fragment_trips, container, false);
        list = v.findViewById(R.id.list);
        empty = v.findViewById(R.id.empty);
        adapter = new TripAdapter(this::confirmDelete);
        list.setLayoutManager(new LinearLayoutManager(getContext()));
        list.setAdapter(adapter);
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        LiveState.addListener(liveListener);
        lastRevision = -1;
        maybeReload();
    }

    @Override
    public void onPause() {
        super.onPause();
        LiveState.removeListener(liveListener);
    }

    private void maybeReload() {
        if (!isAdded()) return;
        if (LiveState.dataRevision == lastRevision) return;
        lastRevision = LiveState.dataRevision;
        reload();
    }

    private void reload() {
        List<Trip> trips = Db.get(requireContext()).listTrips(500);
        adapter.setItems(trips);
        empty.setVisibility(trips.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(trips.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void confirmDelete(final Trip t) {
        new AlertDialog.Builder(requireContext())
                .setTitle("이 주행 기록을 삭제할까요?")
                .setMessage(Fmt.dateTime(t.startTs) + "\n" + Fmt.km(t.km()))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    Db.get(requireContext()).deleteTrip(t.id);
                    LiveState.bumpData();
                    lastRevision = LiveState.dataRevision;
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
