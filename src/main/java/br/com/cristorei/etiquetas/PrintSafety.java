package br.com.cristorei.etiquetas;

/** Detecta bandas muito escuras, que merecem teste em impressoras termicas antigas. */
public final class PrintSafety {
    private PrintSafety() {}

    public static double peak20(ZplDecoder.Raster raster) {
        int height = raster.height();
        int bpr = raster.width() / 8;
        if (height < 20) return 0;
        byte[] dots = raster.dots();
        int[] row = new int[height];
        for (int y=0; y<height; y++) {
            int count = 0;
            for (int x=0;x<bpr;x++) count += Integer.bitCount(dots[y*bpr+x] & 0xFF);
            row[y]=count;
        }
        int sum=0,max=0;
        for (int y=0;y<height;y++) {
            sum+=row[y];
            if (y>=20) sum-=row[y-20];
            if (y>=19) max=Math.max(max,sum);
        }
        return max / 20d;
    }
}
