package com.jph.bpu.library.upload;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.jph.bpu.library.entity.FailInfo;
import com.jph.bpu.library.entity.FileBody;
import com.jph.bpu.library.entity.ResultInfo;
import com.jph.bpu.library.entity.SuccessInfo;
import com.jph.bpu.library.entity.UpdateInfo;
import com.jph.bpu.library.util.GsonUtil;
import com.jph.bpu.library.util.Utils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 断点上传工具类
 *
 * @author JPH
 * @date 2015-10-28 下午1:57:12
 */
public class BreakPointUploadTool {
	private final String TAG=BreakPointUploadTool.class.getSimpleName();
	/**文件上传完成*/
	private final int STATUS_SUCCESS= 0;
	/**获取上传文件起点成功|本节点上传成功*/
	private final int STATUS_CONTINUE = 1;
	/** 网络异常 **/
	private final int STATUS_NETWORK_ERROR = 3;
	/** 获取数据失败 **/
	private final int STATUS_FETCHDATA_ERROR =4;
	/** 分片大小 **/
	private long lPiece = 1024 * 1024 * 10;
	/** 设置socket 超时时长为60s秒 **/
	private final int SO_TIMEOUT = 60 * 1000;
	private Handler mHandler;
	private String serverUrl;
	public BreakPointUploadTool(Handler mHandler,String serverUrl) {
		this.mHandler=mHandler;
		this.serverUrl=serverUrl;
	}

	/**
	 * 上传文件
	 * @param fileBody 要上传文件的实体类
	 * @return
	 * @author JPH
	 * @date 2015-10-28 下午1:57:12
	 */
	public Object uploadFile(FileBody fileBody) {
		ResultInfo resultInfo = getStartPoint(fileBody);// 向服务器获取要上传文件的起点信息
		long codeResult = resultInfo.getCode();// 返回状态码
		if (codeResult != 1) {// 网络异常
			Log.i(TAG, resultInfo.toString());
			return new FailInfo(resultInfo.getMsg(),fileBody.getLocalFilePath());
		}
//		long fileSize =new File(fileBody.getLocalFilePath()).length();// 总计大小
		Log.i(TAG,"filename:" + resultInfo.getFileName());
		if (resultInfo.getStart()>=fileBody.getFileSize()){
			return new SuccessInfo(fileBody.getLocalFilePath(), resultInfo.getPath());
		}
		if (codeResult == STATUS_CONTINUE) {// 获取起点位置成功
			while (true) {
				Log.i(TAG, "------begin upload-------");
				ResultInfo uploadResult=upload(fileBody.getLocalFilePath(),fileBody.getModuleType(), resultInfo.getFileName(),
						resultInfo.getStart(), fileBody.getFileSize());
				resultInfo.setStart(uploadResult.getStart());
				Log.i(TAG,"上传返回值:" + uploadResult.toString());
				long start=uploadResult.getStart();
				if (uploadResult.getCode() == STATUS_CONTINUE) {// 本段上传完成
					if (start == -1) {// 此文件的所有部分全部上传完成
						Log.i(TAG,"------upload finished------\n图片在服务器上保存的路径:"+uploadResult.getPath());
						return new SuccessInfo(fileBody.getLocalFilePath(), uploadResult.getPath());
					} else {// 继续上传
						Log.i(TAG,"-------continue upload--------");
					}
				} else {// 上传失败
					return new FailInfo(uploadResult.getMsg(),fileBody.getLocalFilePath());
				}
			}
		}
		return new FailInfo("未知异常",fileBody.getLocalFilePath());
	}
	/**
	 * 获取上传起始点
	 *
	 * @param fileBody 上传文件的实体类
	 * @return 如：{"start":8338974,"path":
	 *         "/home/software/ftp/pic/2015/04/dir/351f0914cb1161e2f40b5f50dc23c955.zip"
	 *         ,"fileName":"351f0914cb1161e2f40b5f50dc23c955.zip","code":1}
	 * @author JPH
	 * @date 2015-10-28 下午1:57:12
	 */
	private ResultInfo getStartPoint(FileBody fileBody) {
		ResultInfo resultInfo =new ResultInfo();
		HttpURLConnection conn=null;
		String responseContent;
		try {
			File file = new File(fileBody.getLocalFilePath()); // 初始化 File
			String localFileName = file.getName();
			StringBuffer sbUrl = new StringBuffer(serverUrl);
			sbUrl.append("upload?name=")
					.append(localFileName)
					.append("&dirtype=")
					.append(fileBody.getModuleType())
					.append("&type=")
					.append(localFileName.substring(localFileName
							.lastIndexOf('.') + 1)).append("&size=")
					.append(fileBody.getFileSize()).append("&modified=")
					.append(file.lastModified());
			conn= (HttpURLConnection) new URL(sbUrl.toString()).openConnection();
			if (conn.getResponseCode() ==200) {
				responseContent = Utils.getStringFromInputStream(conn.getInputStream());
				resultInfo =(ResultInfo) GsonUtil.convertJson2Object(responseContent, ResultInfo.class, GsonUtil.JSON_JAVABEAN);
				if (resultInfo==null) {
					resultInfo=new ResultInfo();
					resultInfo.setCode(STATUS_FETCHDATA_ERROR);
					resultInfo.setMsg(responseContent);
				}
			} else {
				resultInfo.setCode(STATUS_NETWORK_ERROR);
				resultInfo.setMsg(Utils.getStringFromInputStream(conn.getErrorStream()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			resultInfo.setCode(STATUS_NETWORK_ERROR);
			resultInfo.setMsg(e.toString());
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
		return resultInfo;
	}

	/**
	 * 断点续传
	 * @param localFilePath
	 *            本地文件路径
	 * @param moduleType
	 *            要将文件上传到的文件名（模块名 id,sign,image,jzimage,pickup）
	 * @param returnFileName
	 *            远程文件名从getStartPos返回值中得到
	 * @param startPoint
	 *            起始字节数 1234(字节)
	 * @param fileSize
	 *            文件总大小 234433(字节)
	 * @return {"code":"0","start":-1}code=0表示连接服务器成功，start=-1 表示上传完成
	 * @author JPH
	 * @date 2015-10-28 下午2:12:34
	 */
	@TargetApi(Build.VERSION_CODES.KITKAT)
	@SuppressWarnings("unchecked")
	private ResultInfo upload(String localFilePath, String moduleType,
						 String returnFileName, long startPoint, long fileSize) {
		ResultInfo result=new ResultInfo();
		HttpURLConnection conn=null;
		String responseContent;
		try {
			StringBuffer sbUrl = new StringBuffer(serverUrl);
			sbUrl.append("upload?saveName=").append(returnFileName)
					.append("&dirtype=").append(moduleType);
			conn= (HttpURLConnection) new URL(sbUrl.toString()).openConnection();

			if (startPoint >= fileSize) {//文件已全部上传
				result.setStart(-1);
				result.setCode(STATUS_SUCCESS);
				return result;
			}
			// 计算本次上传的范围
			long endPoint = startPoint + lPiece;//本次上传文件的终点
			if (endPoint >= fileSize) {
				endPoint = fileSize;
			}

			String strRange = startPoint + "-" + String.valueOf(endPoint) + "/"
					+ fileSize;
			strRange = "bytes " + strRange;
			conn.setRequestProperty("Content-Range", strRange);
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setRequestProperty("Content-type", "multipart/form-data;   boundary=---------------------------7d318fd100112");
			conn.setRequestProperty("Connection", "Keep-Alive");
			conn.setFixedLengthStreamingMode(endPoint - startPoint);//上传数据的大小，需要设置，否则禁掉缓存无效
			conn.setUseCaches(false);//禁掉缓存
			outPutData(conn,endPoint,startPoint,localFilePath);
			if (conn.getResponseCode()==200) {
				responseContent = Utils.getStringFromInputStream(conn.getInputStream());
				result = (ResultInfo) GsonUtil.convertJson2Object(responseContent, ResultInfo.class,GsonUtil.JSON_JAVABEAN);
				if (result==null) {
					result=new ResultInfo();
					result.setCode(STATUS_FETCHDATA_ERROR);
					result.setMsg(responseContent);
				}
			} else {
				result.setCode(STATUS_NETWORK_ERROR);
				result.setMsg(Utils.getStringFromInputStream(conn.getErrorStream()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			result.setCode(STATUS_NETWORK_ERROR);
			result.setMsg(e.toString());
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
		return result;
	}

	private void outPutData(HttpURLConnection conn,long lEnd, long lStart, String localFilePath) throws IOException {
		long fileSize=new File(localFilePath).length();
		long completeSize=lStart;
		int bufferLen=1024;//一次读取的文件大小
		RandomAccessFile raf = new RandomAccessFile(localFilePath, "r");// 负责读取数据
		raf.seek(lStart);
		OutputStream os=conn.getOutputStream();
		byte[] buffer = new byte[bufferLen];
		int count= (int) ((lEnd - lStart)/bufferLen);
		for(int i=0;i<count;i++) {
			raf.read(buffer, 0, bufferLen);
			os.write(buffer);
			completeSize+=bufferLen;
			sendUpdateMessage(fileSize,completeSize);
		}
		int lastBufferLen=(int) (lEnd-completeSize);
		if(lastBufferLen>0){
			byte[] lastBuffer=new byte[lastBufferLen];
			raf.read(lastBuffer ,0, lastBufferLen);
			os.write(lastBuffer);
			completeSize+=lastBufferLen;
			sendUpdateMessage(fileSize,completeSize);
		}
		raf.close();
		os.flush();
		os.close();
	}
	/**
	 * 发送更新下载进度的消息
	 */
	private void sendUpdateMessage(long fileSize,long completeSize){
		Message msg=Message.obtain();
		msg.what= UploadHandler.WHAT_UPDATE;
		msg.obj=new UpdateInfo(fileSize,completeSize,true);
		mHandler.sendMessage(msg);
	}
}
