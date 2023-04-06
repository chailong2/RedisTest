package com.hmdp.utils;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;

@Component//这就是我们刚才加入的依赖
public class FtpUtil {

    //ftp服务器ip地址
    private static final String FTP_ADDRESS = "172.16.23.146";
    //端口号
    private static final int FTP_PORT = 21;
    //用户名
    private static final String FTP_USERNAME = "laomeng111";
    //密码
    private static final String FTP_PASSWORD = "2012126846";
    //路径都是/home/加上用户名
    public final String FTP_BASEPATH = "/usr/local/nginx/html/hmdp/imgs/blogs";
    //参数传过来了文件和文件的输入流
    public boolean uploadFile(String originFileName, InputStream input) {
        boolean success = false;
        FTPClient ftp = new FTPClient();//这是最开始引入的依赖里的方法
        ftp.setControlEncoding("utf-8");
        try {
            int reply;
            ftp.connect(FTP_ADDRESS, FTP_PORT);// 连接FTP服务器
            ftp.login(FTP_USERNAME, FTP_PASSWORD);// 登录
            reply = ftp.getReplyCode();//连接成功会的到一个返回状态码
            System.out.println(reply);
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftp.disconnect();
                System.out.println("连接失败");
                return success;
            }
            ftp.setFileType(ftp.BINARY_FILE_TYPE);
            ftp.changeWorkingDirectory(FTP_BASEPATH);//修改操作空间
            String[] dirs=originFileName.split("/");
            String[] mydir={dirs[2],dirs[3]};
            String path=FTP_BASEPATH;
            for (String dir : mydir ) {
                 path=path+"/"+dir;
                if(!ftp.changeWorkingDirectory(path)){
                    if(ftp.makeDirectory(path)){
                        if(! ftp.changeWorkingDirectory(path))
                            throw  new Exception("打开文件夹"+path+"失败");
                    }else{
                        throw  new Exception("创建文件夹"+path+"失败");
                    }
                }
            }
            ftp.enterLocalPassiveMode();
//对了这里说明一下你所操作的文件夹必须要有可读权限，chomd 777 文件夹名//这里我就是用的我的home文件夹
            ftp.storeFile(dirs[4], input);//这里开始上传文件

            System.out.println("连接成功！");

            input.close();
            ftp.logout();
            success = true;
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (ftp.isConnected()) {
                try {
                    ftp.disconnect();
                } catch (IOException ioe) {
                }
            }
        }
        return success;
    }
}


