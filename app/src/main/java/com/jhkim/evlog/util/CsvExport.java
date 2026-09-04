package com.jhkim.evlog.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.jhkim.evlog.db.Charge;
import com.jhkim.evlog.db.Db;
import com.jhkim.evlog.db.Trip;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 기록을 CSV로 내보냅니다. 엑셀에서 한글이 깨지지 않도록 BOM을 붙입니다. */
public final class CsvExport {

    private CsvExport() {
    }

    public static File[] write(Context ctx) throws IOException {
        Db db = Db.get(ctx);
        File base = ctx.getExternalFilesDir(null);
        File dir = base != null ? new File(base, "export") : null;
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            dir = new File(ctx.getFilesDir(), "export");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        if (!dir.isDirectory()) {
            throw new IOException("내보낼 폴더를 만들 수 없습니다: " + dir);
        }
        String stamp = Fmt.fileStamp(System.currentTimeMillis());

        File tripFile = new File(dir, "주행기록_" + stamp + ".csv");
        try (Writer w = open(tripFile)) {
            w.write("시작,종료,주행거리(km),소요시간(분),평균속도(km/h),최고속도(km/h),"
                    + "시작잔량(%),종료잔량(%),사용전력(kWh),전비(km/kWh),전력소비(Wh/km),출처\n");
            for (Trip t : db.listTrips(100000)) {
                w.write(String.format(Locale.KOREA,
                        "%s,%s,%.2f,%d,%.1f,%.1f,%s,%s,%s,%s,%s,%s\n",
                        Fmt.iso(t.startTs), Fmt.iso(t.endTs), t.km(), t.totalS / 60,
                        t.avgKmh, t.maxKmh,
                        num(t.startSoc), num(t.endSoc),
                        t.usedWh > 0 ? String.format(Locale.KOREA, "%.2f", t.usedWh / 1000.0) : "",
                        t.efficiencyKmPerKwh() > 0
                                ? String.format(Locale.KOREA, "%.2f", t.efficiencyKmPerKwh()) : "",
                        t.whPerKm() > 0 ? String.format(Locale.KOREA, "%.0f", t.whPerKm()) : "",
                        "car".equals(t.source) ? "차량" : "GPS"));
            }
        }

        File chargeFile = new File(dir, "충전기록_" + stamp + ".csv");
        try (Writer w = open(chargeFile)) {
            w.write("시작,종료,방식,충전량(kWh),최대출력(kW),요금(원),시작잔량(%),종료잔량(%),입력,메모\n");
            for (Charge c : db.listCharges(100000)) {
                w.write(String.format(Locale.KOREA,
                        "%s,%s,%s,%.2f,%s,%.0f,%s,%s,%s,%s\n",
                        Fmt.iso(c.startTs), Fmt.iso(c.endTs),
                        c.isDc() ? "급속" : "완속", c.kwh(),
                        c.maxKw > 0 ? String.format(Locale.KOREA, "%.1f", c.maxKw) : "",
                        c.cost, num(c.startSoc), num(c.endSoc),
                        c.manual ? "직접" : "자동",
                        escape(c.note)));
            }
        }

        return new File[]{tripFile, chargeFile};
    }

    private static Writer open(File f) throws IOException {
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), Charset.forName("UTF-8"));
        w.write(0xFEFF); // 엑셀에서 한글이 깨지지 않도록 BOM
        return w;
    }

    private static String num(double v) {
        return v < 0 ? "" : String.format(Locale.KOREA, "%.1f", v);
    }

    private static String escape(String s) {
        if (s == null) return "";
        String out = s.replace("\"", "'").replace(",", " ").replace("\n", " ");
        return out;
    }

    /** 내보낸 파일을 다른 앱으로 공유하는 인텐트를 만듭니다. */
    public static Intent shareIntent(Context ctx, File[] files) {
        ArrayList<Uri> uris = new ArrayList<>();
        String authority = ctx.getPackageName() + ".fileprovider";
        for (File f : files) {
            uris.add(FileProvider.getUriForFile(ctx, authority, f));
        }
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("text/csv");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(i, "CSV 내보내기");
    }

    public static List<String> names(File[] files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getName());
        return out;
    }
}
