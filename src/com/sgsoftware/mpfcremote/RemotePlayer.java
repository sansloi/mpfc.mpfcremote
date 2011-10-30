package com.sgsoftware.mpfcremote;

import javax.net.SocketFactory;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class RemotePlayer {
	
	Socket m_sock;
	InputStream m_input;
	OutputStream m_output;

	ReadThread m_readThread;

	LinkedBlockingQueue<String> m_respQueue;
	
	public enum PlayStatus
	{
		PLAYING, STOPPED, PAUSED;

		public static PlayStatus parse(String s)
		{
			if (s.equals("playing"))
				return PLAYING;
			else if (s.equals("paused"))
				return PAUSED;
			else if (s.equals("stopped"))
				return STOPPED;
			else
				return STOPPED;
		}
	}

	public class CurSong
	{
		String title;
		int length;
		int curPos;
		int posInList;
		PlayStatus status;
	}

	public class Song
	{
		String name;
		int length;
	}

	private CurSong m_curSong;
	private ArrayList<Song> m_playList;
	private int m_totalLength;
	
	public RemotePlayer(String addr, int port,
						INotificationHandler notificationHandler) 
			throws java.net.UnknownHostException, java.io.IOException
	{
		m_respQueue = new LinkedBlockingQueue<String>();

		SocketFactory sockFactory = SocketFactory.getDefault();
		m_sock = sockFactory.createSocket(addr, port);
		m_input = m_sock.getInputStream();
		m_output = m_sock.getOutputStream();

		m_readThread = new ReadThread(m_input, m_respQueue, 
				notificationHandler);
		m_readThread.start();
		
		syncCurSong();
		syncPlaylist();
	}

	public void destroy()
	{
		send("bye\n");
		try {
			m_sock.close();
		}
		catch (java.io.IOException e)
		{ }
		m_readThread.interrupt();
	}

	public CurSong getCurSong()
	{
		return m_curSong;
	}

	public ArrayList<Song> getPlayList()
	{
		return m_playList;
	}

	public int getTotalLength()
	{
		return m_totalLength;
	}
	
	public void pause()
	{
		send("pause\n");
	}
	
	public void next()
	{
		send("next\n");
	}
	
	public void prev()
	{
		send("prev\n");
	}
	
	public void timeBack()
	{
		send("time_back\n");
	}

	public void play(int pos)
	{
		send(String.format("play %d\n", pos));
	}
	
	public void refresh()
	{
		syncCurSong();
		syncPlaylist();
	}
	
	public void clear()
	{
		send("clear_playlist\n");
	}

	public void add(String name)
	{
		// Escape special symbols
		name = name.replace("*", "\\*");
		name = name.replace("?", "\\?");
		name = name.replace("[", "\\[");
		name = name.replace("]", "\\]");
		name = name.replace("~", "\\~");
		send(String.format("add \"%s\"\n", name));
	}

	public void removeSong(int pos)
	{
		send(String.format("remove %d\n", pos));
	}

	public void queueSong(int pos)
	{
		send(String.format("queue %d\n", pos));
	}

	public class DirEntry implements Comparable
	{
		public String name;
		public boolean isDir;
		
		public int compareTo(Object o)
		{
			return name.compareTo(((DirEntry)o).name);
		}
	}

	DirEntry[] listDir(String dir)
	{
		if (!send(String.format("list_dir \"%s\"\n", dir)))
			return new DirEntry[] {};

		try
		{
			String r = m_respQueue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
			if (r != null)
				return parseListDir(r);
		}
		catch (java.lang.InterruptedException e)
		{
		}
		return new DirEntry[] {};
	}

	public void incrementCurTime(int ms) {
		if (m_curSong == null)
			return;
		m_curSong.curPos += ms/1000;
	}

	private void syncPlaylist()
	{
		m_totalLength = 0;

		if (!send("get_playlist\n"))
			return;
		try
		{
			String r = m_respQueue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
			if (r != null)
				parsePlaylist(r);
		}
		catch (java.lang.InterruptedException e)
		{
			return;
		}
	}
	
	private void syncCurSong()
	{
		if (!send("get_cur_song\n"))
			return;
		try
		{
			String r = m_respQueue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
			if (r != null)
				parseCurSong(r);
		}
		catch (java.lang.InterruptedException e)
		{
			return;
		}
	}

	private void parsePlaylist(String s)
	{
		m_playList = new ArrayList<Song>();

		try
		{
			JSONArray js = new JSONArray(new JSONTokener(s));
			for ( int i = 0; i < js.length(); i++ )
			{
				JSONObject obj = js.getJSONObject(i);
				Song song = new Song();
				song.name = obj.getString("title");
				song.length = obj.getInt("length");
				m_totalLength += song.length;
				m_playList.add(song);
			}
		}
		catch (org.json.JSONException e)
		{
		}
	}

	private void parseCurSong(String s)
	{
		try
		{
			JSONObject js = new JSONObject(new JSONTokener(s));
			m_curSong = new CurSong();
			m_curSong.title = js.getString("title");
			m_curSong.length = js.getInt("length");
			m_curSong.curPos = js.getInt("time");
			m_curSong.posInList = js.getInt("position");
			m_curSong.status = PlayStatus.parse(js.getString("play_status"));
		}
		catch (org.json.JSONException e)
		{
			m_curSong = null;
		}
	}
	
	private DirEntry[] parseListDir(String s)
	{
		try
		{
			JSONArray js = new JSONArray(new JSONTokener(s));
			DirEntry[] res = new DirEntry[js.length()];
			for ( int i = 0; i < js.length(); i++ )
			{
				JSONObject obj = js.getJSONObject(i);
				res[i] = new DirEntry();
				res[i].name = obj.getString("name");
				res[i].isDir = (obj.getString("type").equals("d"));
			}
			java.util.Arrays.sort(res);
			return res;
		}
		catch (org.json.JSONException e)
		{
			return new DirEntry[] {};
		}
	}

	private boolean send(String s)
	{
		try
		{
			m_output.write(s.getBytes());
			return true;
		}
		catch (java.io.IOException e)
		{
			return false;
		}
	}


	public enum MsgType
	{
		RESPONSE, NOTIFICATION
	}

	private class ReadThread extends Thread
	{
		InputStream m_stream;
		LinkedBlockingQueue<String> m_respQueue;
		INotificationHandler m_notificationHandler;
		
		private class Header
		{
			public int msgLen;
			public MsgType msgType;

			public Header(int len, MsgType type)
			{
				msgLen = len;
				msgType = type;
			}
		}

		public ReadThread(InputStream stream, LinkedBlockingQueue<String> respQueue,
				          INotificationHandler notificationHandler)
		{
			m_stream = stream;
			m_respQueue = respQueue;
			m_notificationHandler = notificationHandler;
		}
		
		@Override
		public void run()
		{
			while (true)
			{
				try
				{
					readMsg();
				}
				catch (java.io.IOException e)
				{
					break;
				}
				catch (java.lang.InterruptedException e)
				{
					break;
				}
			}
		}

		private void readMsg()
			throws java.io.IOException, java.lang.InterruptedException
		{
			Header h = readHeader();
			if (h == null)
				return;

			byte[] bs = new byte[h.msgLen];
			if (!readExact(bs))
				return;

			if (h.msgType == MsgType.RESPONSE)
			{
				m_respQueue.put(new String(bs));
			}
			else if (h.msgType == MsgType.NOTIFICATION)
			{
				m_notificationHandler.processNotification(new String(bs));
			}
		}

		private Header readHeader()
			throws java.io.IOException
		{
			// Read 'Msg-Length: '
			if (!readExactString("Msg-Length: "))
				return null;

			// Read length itself
			int len = 0;
			for ( ;; )
			{
				int b = m_stream.read();
				if (b >= '0' && b <= '9')
				{
					len *= 10;
					len += (b - '0');
				}
				else if (b == '\n')
					break;
				else
					return null;
			}

			// Read 'Msg-Type: '
			if (!readExactString("Msg-Type: "))
				return null;

			// Read message type
			int b = m_stream.read();
			m_stream.read();
			if (b == 'r')
				return new Header(len, MsgType.RESPONSE);
			else if (b == 'n')
				return new Header(len, MsgType.NOTIFICATION);
			else
				return null;
		}

		private boolean readExactString(String s)
			throws java.io.IOException
		{
			byte[] bs = new byte[s.length()];
			if (!readExact(bs))
				return false;
			String rs = new String(bs);
			if (!rs.equals(s))
				return false;
			return true;
		}

		private boolean readExact(byte[] bs)
			throws java.io.IOException
		{
			int len = bs.length;
			int off = 0;
			while (len > 0)
			{
				int r = m_stream.read(bs, off, len);
				if (r < 0)
					return false;
				len -= r;
				off += r;
			}
			return true;
		}
	}
}
