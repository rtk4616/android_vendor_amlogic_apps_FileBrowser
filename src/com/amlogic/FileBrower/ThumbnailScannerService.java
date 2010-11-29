package com.amlogic.FileBrower;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import com.amlogic.FileBrower.FileBrowerDatabase.ThumbnailCursor;

public class ThumbnailScannerService extends Service implements Runnable {
	private static final String TAG = "ThumbnailScannerService";
	
	public static final String ACTION_THUMBNAIL_SCANNER_FINISHED
						= "com.amlogic.FileBrower.THUMBNAIL_SCANNER_FINISHED";
	
	private static FileBrowerDatabase db;
	
    private volatile Looper mServiceLooper;
    private volatile ServiceHandler mServiceHandler;
	private PowerManager.WakeLock mWakeLock;
	
    @Override
    public void onCreate()
    {
    	db = new FileBrowerDatabase(this); 
        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        // Start up the thread running the service.  Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block.
        Thread thr = new Thread(null, this, "ThumbnailScannerService");
        thr.start();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        while (mServiceHandler == null) {
            synchronized (this) {
                try {
                    wait(100);
                } catch (InterruptedException e) {
                }
            }
        }

        if (intent == null) {
            Log.e(TAG, "Intent is null in onStartCommand: ",
                new NullPointerException());
            return Service.START_NOT_STICKY;
        }

        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = startId;
        msg.obj = intent.getExtras();
        mServiceHandler.sendMessage(msg);

        // Try again later if we are killed before we can finish scanning.
        return Service.START_REDELIVER_INTENT;
    }
    
    @Override
    public void onDestroy()
    {
    	if (db != null) db.close();
        // Make sure thread has started before telling it to quit.
        while (mServiceLooper == null) {
            synchronized (this) {
                try {
                    wait(100);
                } catch (InterruptedException e) {
                }
            }
        }
        mServiceLooper.quit();
    }
    
    public void run()
    {
        // reduce priority below other background threads to avoid interfering
        // with other services at boot time.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND +
                Process.THREAD_PRIORITY_LESS_FAVORABLE);
        Looper.prepare();

        mServiceLooper = Looper.myLooper();
        mServiceHandler = new ServiceHandler();

        Looper.loop();
    }
    
    private final class ServiceHandler extends Handler
    {
        @Override
        public void handleMessage(Message msg)
        {
            Bundle arguments = (Bundle) msg.obj;            
            String dir_path = arguments.getString("dir_path");
            String scan_type = arguments.getString("scan_type");
            
            try {
            	if (scan_type != null) {
            		if (scan_type.equals("all")) {
            	        long start_time, end_time;
            	        start_time = System.currentTimeMillis();
            	        //createAllThumbnailsInDir("/mnt/flash");
            	        //createAllThumbnailsInDir("/mnt/sdcard");
            	        //createAllThumbnailsInDir("/mnt/usb");
            	        File dir = new File("/mnt");
            			if (dir.exists() && dir.isDirectory()) {
            				if (dir.listFiles() != null) {
            					if (dir.listFiles().length > 0) {
            						for (File file : dir.listFiles()) {
            							if (file.isDirectory()) {
            								String path = file.getAbsolutePath();            								
            								if (path.equals("/mnt/flash") ||
            									path.equals("/mnt/sdcard") ||
            									path.equals("/mnt/usb") ||
            									path.startsWith("/mnt/sd")) {
            									createAllThumbnailsInDir(path);
            								}
            							}
            						}
            					}
            				}
            			}
            	        
            			end_time = System.currentTimeMillis();
            			Log.w("createThumbnailsInAllDev",              					
            					" time:" + (end_time - start_time) + "ms");
            			sendBroadcast(new Intent(ACTION_THUMBNAIL_SCANNER_FINISHED));  
            			
            		} else if (scan_type.equals("dev")) {
            			if (dir_path != null) {
                	        long start_time, end_time;
                	        start_time = System.currentTimeMillis();
                			createAllThumbnailsInDir(dir_path);
                			end_time = System.currentTimeMillis();
                			Log.w("createThumbnailsInDev", "dev:" + dir_path +             					
                					" time:" + (end_time - start_time) + "ms");
                			sendBroadcast(new Intent(ACTION_THUMBNAIL_SCANNER_FINISHED));  
            			}
            			
            		} else if (scan_type.equals("dir")) {
            			if (dir_path != null) {
                			if (createThumbnailsInDir(dir_path) > 0) {
                				sendBroadcast(new Intent(ACTION_THUMBNAIL_SCANNER_FINISHED));	
                			}
            			}
            			
            		}
            	}
            } catch (Exception e) {
                Log.e(TAG, "Exception in handleMessage", e);
            }

            stopSelf(msg.arg1);
        }
    }
    
	@Override
	public IBinder onBind(Intent arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	private int createThumbnail(String file_path) {		
		 int count = 0;
		 if (file_path != null && db != null) {
			 if (FileOp.isPhoto(file_path) && new File(file_path).exists()) {					 
				 ThumbnailCursor cc = null;
				 try {
					 cc = db.checkThumbnailByPath(file_path);
					 if (cc != null && cc.moveToFirst()) {
						 if (cc.getCount() > 0)
							 return 0;
					 }
				 } finally {
					 if(cc != null) cc.close();
				 }
				 
				 BitmapFactory.Options options = new BitmapFactory.Options();
				 options.inJustDecodeBounds = true;
				 Bitmap bitmap = BitmapFactory.decodeFile(file_path, options);
				 //Log.i("old ..........size:", "w" + options.outWidth + " h" + options.outHeight);
				 int samplesize = (int) (options.outHeight / 96);
				 if (samplesize <= 0) samplesize = 1;
				 //Log.i("sampleSize.........:", " " + samplesize);
				 
				 options.inSampleSize = samplesize;
				 options.inJustDecodeBounds = false;
				 bitmap = BitmapFactory.decodeFile(file_path, options);				 
				 //Log.i("new ..........size1:", "w" + bitmap.getWidth() + " h" + bitmap.getWidth());
				 
				 bitmap = ThumbnailUtils.extractThumbnail(bitmap, 96, 96);
				 //Log.i("new ..........size2:", "w" + bitmap.getWidth() + " h" + bitmap.getWidth());
				 
				 if (bitmap != null) {
				     ByteArrayOutputStream os = new ByteArrayOutputStream();
				     bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
				     db.addThumbnail(file_path, os.toByteArray());
				     try {
				    	 os.close();
				     } catch (IOException e) {
				    	 // TODO Auto-generated catch block
				    	 e.printStackTrace();
				     }
				     count++;
				 }
			 }
		 }
		 return count;
	}
	
	private int createThumbnailsInDir(String dir_path) {	
        // don't sleep while scanning
        mWakeLock.acquire();
		int count = 0;
        long start_time, end_time;
        start_time = System.currentTimeMillis();		
		if (dir_path != null) {
			if (!dir_path.startsWith("/mnt/sdcard") &&
				!dir_path.startsWith("/mnt/flash") &&
				!dir_path.startsWith("/mnt/usb") &&
				!dir_path.startsWith("/mnt/sd")) 				
				return 0;			
			
			File dir = new File(dir_path);
			if (dir.exists() && dir.isDirectory()) {
				if (dir.listFiles() != null) {
					if (dir.listFiles().length > 0) {
						for (File file : dir.listFiles()) {
							if (file.isFile() && FileOp.isPhoto(file.getName())) {
								count += createThumbnail(file.getAbsolutePath());
							}
						}
					}
				}
			}
		}
		end_time = System.currentTimeMillis();
		Log.w("createThumbnailsInDir", "dir:" + dir_path + 
				" files:" + count +
				" time:" + (end_time - start_time) + "ms");
		mWakeLock.release();
		return count;
	}
	
	private void createAllThumbnailsInDir(String dir_path) {
        // don't sleep while scanning
        mWakeLock.acquire();
        
		if (dir_path != null) {
			if (!dir_path.startsWith("/mnt/sdcard") &&
				!dir_path.startsWith("/mnt/flash") &&
				!dir_path.startsWith("/mnt/usb") &&
				!dir_path.startsWith("/mnt/sd")) 				
				return;	
			
			File dir = new File(dir_path);
			if (dir.exists() && dir.isDirectory()) {
				if (dir.listFiles() != null) {
					if (dir.listFiles().length > 0) {
						for (File file : dir.listFiles()) {
							if (file.isDirectory()) {
								createAllThumbnailsInDir(file.getAbsolutePath());
							} else if (file.isFile() && FileOp.isPhoto(file.getName())) {
								createThumbnail(file.getAbsolutePath());
							}
						}
					}
				}				
			}
		}	

		mWakeLock.release();		
		
	}		

	
}