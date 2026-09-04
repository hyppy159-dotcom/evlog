package com.jhkim.evlog.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.jhkim.evlog.Prefs;
import com.jhkim.evlog.R;
import com.jhkim.evlog.db.Charge;
import com.jhkim.evlog.db.Db;
import com.jhkim.evlog.logger.LiveState;
import com.jhkim.evlog.util.Fmt;

import java.util.List;

public class ChargesFragment extends Fragment {

    private RecyclerView list;
    private TextView empty;
    private ChargeAdapter adapter;
    private int lastRevision = -1;

    private final Runnable liveListener = this::maybeReload;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle saved) {
        View v = inflater.inflate(R.layout.fragment_charges, container, false);
        list = v.findViewById(R.id.list);
        empty = v.findViewById(R.id.empty);
        MaterialButton add = v.findViewById(R.id.btn_add);
        add.setOnClickListener(b -> showAddDialog());

        adapter = new ChargeAdapter(this::confirmDelete);
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
        List<Charge> charges = Db.get(requireContext()).listCharges(500);
        adapter.setItems(charges);
        empty.setVisibility(charges.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(charges.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showAddDialog() {
        View form = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_charge, null, false);
        final EditText inKwh = form.findViewById(R.id.in_kwh);
        final EditText inCost = form.findViewById(R.id.in_cost);
        final EditText inNote = form.findViewById(R.id.in_note);
        final RadioButton radioDc = form.findViewById(R.id.radio_dc);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.add_charge)
                .setView(form)
                .setPositiveButton(R.string.save, (d, w) -> {
                    double kwh = parse(inKwh.getText().toString());
                    if (kwh <= 0) {
                        Toast.makeText(getContext(), "충전량을 입력해 주세요.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean dc = radioDc.isChecked();
                    double cost;
                    String costText = inCost.getText().toString();
                    if (TextUtils.isEmpty(costText.trim())) {
                        float rate = dc ? Prefs.rateDc(requireContext()) : Prefs.rateAc(requireContext());
                        cost = kwh * rate;
                    } else {
                        cost = parse(costText);
                    }

                    Charge c = new Charge();
                    long now = System.currentTimeMillis();
                    c.startTs = now;
                    c.endTs = now;
                    c.addedWh = kwh * 1000.0;
                    c.kind = dc ? Charge.DC : Charge.AC;
                    c.cost = cost;
                    c.maxKw = -1;
                    c.startSoc = -1;
                    c.endSoc = -1;
                    c.manual = true;
                    c.note = inNote.getText().toString();

                    Db.get(requireContext()).insertCharge(c);
                    LiveState.bumpData();
                    lastRevision = LiveState.dataRevision;
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDelete(final Charge c) {
        new AlertDialog.Builder(requireContext())
                .setTitle("이 충전 기록을 삭제할까요?")
                .setMessage(Fmt.dateTime(c.startTs) + "\n" + Fmt.kwh(c.kwh()) + " · " + Fmt.won(c.cost))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    Db.get(requireContext()).deleteCharge(c.id);
                    LiveState.bumpData();
                    lastRevision = LiveState.dataRevision;
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.trim().replace(",", ""));
        } catch (Exception e) {
            return -1;
        }
    }
}
