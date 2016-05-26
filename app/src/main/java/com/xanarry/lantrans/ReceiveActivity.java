package com.xanarry.lantrans;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.xanarry.lantrans.utils.FileDesc;
import com.xanarry.lantrans.minterfaces.ProgressListener;
import com.xanarry.lantrans.network.TcpServer;
import com.xanarry.lantrans.network.UdpServer;
import com.xanarry.lantrans.utils.Configuration;
import com.xanarry.lantrans.utils.ItemsListAdapter;
import com.xanarry.lantrans.utils.Utils;
import com.xanarry.lantrans.utils.ViewHolder;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;

public class ReceiveActivity extends AppCompatActivity {
    private ProgressDialog progressDialog;
    private String savePath;
    private String TAG;
    private ListView listView;
    private Button startRecvBtn;
    private Button selectDirBtn;
    private ArrayList<FileDesc> files;
    private ArrayList<Integer> progressRecords = new ArrayList<>();
    private ArrayList<Integer> speedRecords = new ArrayList<>();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receiver);
        TAG = ReceiveActivity.class.getName();
        listView = (ListView) findViewById(R.id.receivefileListView);
        startRecvBtn = (Button) findViewById(R.id.startRecvBtn);
        selectDirBtn = (Button) findViewById(R.id.selectDirBtn);

        setTitle("接收文件");

        startRecvBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (savePath == null || savePath.length() == 0) {
                    Utils.showDialog(ReceiveActivity.this, "提示", "请选择文件保存位置");
                } else {
                    new ReceiveFileTask().execute();
                }
            }
        });

        selectDirBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getBaseContext(), FileSelectorActivity.class);
                intent.putExtra(FileSelectorActivity.keyClassName, MainActivity.class.getName());
                intent.putExtra(FileSelectorActivity.keyIsSelectFile, false);
                intent.putExtra(FileSelectorActivity.keyIsSingleSelector, true);
                startActivityForResult(intent, FileSelectorActivity.requestCodeSingleFile);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (data == null || data.getExtras() == null) {
            return;
        }
        if (requestCode == FileSelectorActivity.requestCodeSingleFile) {
            ArrayList<String> list = data.getStringArrayListExtra(FileSelectorActivity.keyFilePaths);
            StringBuilder builder = new StringBuilder();
            if (list != null && list.size() >= 1) {
                savePath = list.get(0);
            }
            setTitle("保存文件到:" + savePath);
        }
    }

    void generateRecvFileList() {
        ArrayList<HashMap<String, String>> itmeList = loadFileList(files);
        ItemsListAdapter adapter = new ItemsListAdapter(getApplicationContext(), itmeList, progressRecords, speedRecords);
        listView.setAdapter(adapter);
    }

    private ArrayList<HashMap<String, String>> loadFileList(ArrayList<FileDesc> files) {
        ArrayList<HashMap<String, String>> fileList = new ArrayList<>();
        for (FileDesc file : files) {
            HashMap<String, String> item = new HashMap<>();
            item.put(SendActivity.FILENAME_MK, file.getName());
            item.put(SendActivity.FILESIZE_MK, Utils.getHumanReadableSize(file.getLength()));
            item.put(SendActivity.PROGRESS_MK, "0");
            item.put(SendActivity.SPEED_MK, "等待中");
            fileList.add(item);
        }
        return fileList;
    }


    class ReceiveFileTask extends AsyncTask<String, Integer, Integer> {
        private InetAddress senderIP;

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(ReceiveActivity.this);
            progressDialog.setTitle("提示");
            progressDialog.setMessage("正在等待发送方发送文件···");
            //将进度条设置为水平风格，让其能够显示具体的进度值
            //dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL) ;
            //dialog.setCancelable(false) ; //用了这个方法之后，直到图片下载完成，进度条才会消失（即使在这之前点击了屏幕）
            progressDialog.show();
        }

        @Override
        protected Integer doInBackground(String... params) {
            //-3等待主机, -2发现主机 -1更新列表
            //等待主机被发现
            Log.e(TAG, "start udp server");
            UdpServer udpServer = new UdpServer(Configuration.UDP_PORT);

            DatagramPacket senderPacket = udpServer.waitClient();
            udpServer.close();

            Log.e(TAG, "seder:" + senderPacket.getAddress().getHostName() + " msg:" + Utils.getMessage(senderPacket.getData()));
            senderIP = senderPacket.getAddress();

            //已经发现主机
            publishProgress(-2, 0, 0);
            Log.e(TAG, "update progress: 找到主机");

            //启动Tcp server
            TcpServer tcpServer = new TcpServer(Configuration.currentTcpPort, new ProgressListener() {
                @Override
                public void updateProgress(int filePositon, long hasGot, long totalSize, int speed) {
                    int progress = new Double(100.0 * (double) hasGot / (double) totalSize).intValue();
                    publishProgress(filePositon, progress, speed);
                    progressRecords.set(filePositon, progress);
                }
            });

            Log.e(TAG, "start tcp server waiting");
            //等待与发送方建立Tcp连接
            files = tcpServer.waitSenderConnect();
            progressRecords.clear();
            speedRecords.clear();
            for (int i = 0; i < files.size(); i++) {
                progressRecords.add(0);//初始化每个文件的进度记录
                speedRecords.add(0);
            }

            Log.e(TAG, files.toString());//文件发送可能失败
            publishProgress(-1, 0, 0);
            return tcpServer.recieveFile(files, savePath);
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            final int position = values[0];
            final int progress = values[1];
            final long speed = values[2];
            if (position == -2) {
                progressDialog.setMessage("找到发送者在:" + senderIP.getHostName() + "\n正在建立连接");
            } else if (position == -1) {
                generateRecvFileList();
            } else {
                progressDialog.dismiss();
                String strspeed = Utils.getHumanReadableSize(speed * 1024) + "/S";

                int firstVisiblePosition = listView.getFirstVisiblePosition();
                int lastVisiblePosition = listView.getLastVisiblePosition();
                if (position >= firstVisiblePosition && position <= lastVisiblePosition) {
                    View view = listView.getChildAt(position - firstVisiblePosition);
                    if (view.getTag() instanceof ViewHolder) {
                        ViewHolder vh = (ViewHolder) view.getTag();
                        vh.progressBar.setProgress(progress);
                        vh.progressText.setText(progress + "%");
                        if (progress == 100) {
                            vh.speedText.setText("已完成");
                        } else {
                            vh.speedText.setText(strspeed);
                        }
                    }
                }
                setTitle("正在接收[" + (position + 1) + "/" + speedRecords.size() + "]");
            }
        }

        @Override
        protected void onPostExecute(Integer finishedCount) {
            AlertDialog.Builder finishDialogBuilder = new AlertDialog.Builder(ReceiveActivity.this);// 定义弹出框
            finishDialogBuilder.setTitle("提示");// 设置标题
            finishDialogBuilder.setMessage(finishedCount + "个文件已经成功发送!");// 设置信息主体
            finishDialogBuilder.setNegativeButton("确定",// 设置取消的信息
                    new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();// 直接关闭对话框
                        }
                    });
            finishDialogBuilder.create().show();
            Configuration.currentTcpPort -= 1;
        }
    }
}