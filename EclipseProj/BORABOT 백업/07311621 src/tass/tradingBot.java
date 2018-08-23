package tass;


import exchangeAPI.*;
import Indicator.*;
import base.DB;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Stack;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.servlet.http.HttpSession;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.*;

import base.SendMail;

public class tradingBot {
	
	//From DB
	private static String API_KEY = "8615ae2296b94e91a7703364ed223abd";
	private static String Secret_KEY = "33be781743984f57ac4dfe5cc114dfbd";
	
	//From frontend
	private static String exchange;
	private static String coin;
	private static String base;
	private static int interval;
	private static String botName;
	private static String email;
	private static String strategyName;
	private static String buyingSetting;
	private static String sellingSetting;
	private static String startDate; // 일단 가지고만 있기
	private static String endDate; // 루프 탈출 요소
	// 1 : 대기 , 0 : 종료
	private static int errorHandling;
	
	// optional
	private static double priceBuyUnit;
	private static double numBuyUnit;
	private static double priceSellUnit;
	private static double numSellUnit;
	
	// 보통은 0 -> 이것도 디비 strategy에서 뽑아와야함 ㅇㅇㅇ , 통신으로 받는거 ㄴㄴ
	private double buyCriteria;
	private double sellCriteria;
	
	public tradingBot(String email, String exchange, String botName, String coin, String base, int interval, String startDate, String endDate, String strategyName, String buyingSetting, String sellingSetting, double priceBuyUnit, double priceSellUnit, double numBuyUnit, double numSellUnit, int error) {
		
		this.exchange = exchange;
		this.coin = coin;
		this.base = base;
		this.interval = interval;
		this.botName = botName;
		this.email = email;
		this.strategyName = strategyName;
		this.buyingSetting = buyingSetting;
		this.sellingSetting = sellingSetting;
		this.priceBuyUnit = priceBuyUnit;
		this.priceSellUnit = priceSellUnit;
		this.numBuyUnit = numBuyUnit;
		this.numSellUnit = numSellUnit;
		this.startDate = startDate;
		this.endDate = endDate;
		this.errorHandling = error;
	}
	
	private static CryptowatchAPI crypt = new CryptowatchAPI(20, 60);
	
	public void botStart() {
		
		final exAPI exAPIobj;

	    // DB의 사용자 key를 받아옴
		String selectSql = String.format("SELECT api_key, secret_key from customer_key where email=\'%s\' and exchange_name=\'%s\'", email, exchange);

		DB useDB = new DB(); 
		
		try {
			ResultSet rs = useDB.Query(selectSql, "select");
			while(rs.next()) {
				API_KEY = rs.getString("api_key");
				Secret_KEY = rs.getString("secret_key");
			}
		} catch (SQLException e) {
			e.printStackTrace();			
		}		
		
		if(exchange.equals("bithumb")) {
			exAPIobj = (exAPI) new BithumbAPI(API_KEY, Secret_KEY);
			base = "krw";
		}
		else if(exchange.equals("bittrex")) {
			exAPIobj = (exAPI) new BittrexAPI(API_KEY, Secret_KEY, 10, 10);
			base = "usd";
		}
		else if(exchange.equals("binance")) {
			exAPIobj = (exAPI) new BinanceAPI(API_KEY, Secret_KEY, 10, 10);	
			base = "btc";
		}
		else{
			exAPIobj = (exAPI) new CoinoneAPI(API_KEY, Secret_KEY, 10, 10);	
			base = "krw";
		}
		
		System.out.println(coin);
		//trade DB insert!
		double initialCoinNum = exAPIobj.getBalance(coin);
		double initialBalance = exAPIobj.getBalance(base);
		// 초기 진행 상태 = 1(시작) / 초기 최종자산 = -1000으로 표시(null)
		String initialTradeSql = String.format(" INSERT INTO trade VALUES( \"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%s,\"%s\",\"%s\",\"%s\",\"%s\",%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s )", email, botName, exchange, coin, base, strategyName, interval, startDate, endDate, buyingSetting, sellingSetting, priceBuyUnit, priceSellUnit, numBuyUnit, numSellUnit, buyCriteria, sellCriteria, 1, initialBalance, initialCoinNum, -1000, -1 , -1 );
		try {
			useDB.Query(initialTradeSql, "insert");
		} catch (SQLException e) {
			e.printStackTrace();			
		}
		
		String settingSelectSql = String.format("SELECT strategy_content FROM custom_strategy WHERE email = \"%s\" and strategy_name = \"%s\"; ", email, strategyName);
		
		String strategySettingJson="";
		
		try {
			ResultSet rsTemp = useDB.Query(settingSelectSql, "select");
			if(rsTemp.next()) {
				strategySettingJson = rsTemp.getString(1);
			}
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		JsonParser parser = new JsonParser();
		JsonElement element = parser.parse(strategySettingJson);
		JsonObject jsnObj = element.getAsJsonObject();
		
		buyCriteria = jsnObj.get("buyCriteria").getAsInt();
		sellCriteria = jsnObj.get("sellCriteria").getAsInt();
		String expList[] = jsnObj.get("expList").getAsString().split(",");
		
		JsonObject indicatorListJs = jsnObj.get("indicatorList").getAsJsonObject();
		calcIndicator[] indicatorCalcer = new calcIndicator[indicatorListJs.size()];
		int weightList[] = new int[indicatorListJs.size()];
		
		// 배열에다가 해당하는 지표 객체 담기 (각각 개별 파라미터 및 웨이트 적용)
		for(int i = 0; i < indicatorListJs.size(); i++) {
			String indexOrder = i+"";
			String indicator = indicatorListJs.get(indexOrder).getAsJsonObject().get("indicator").getAsString();
			int weight = indicatorListJs.get(indexOrder).getAsJsonObject().get("weight").getAsInt();
			weightList[i] = weight;
			//System.out.println("tradingBot : " + indicator);
			try {
				if(indicator.equals("BollingerBand")) {
					int period = indicatorListJs.get(indexOrder).getAsJsonObject().get("period").getAsInt();
					int mul = indicatorListJs.get(indexOrder).getAsJsonObject().get("mul").getAsInt();
					
					indicatorCalcer[i] = new BollingerBand(period, mul, crypt, exchange, coin, base, interval);
				}
				else if(indicator.equals("CCI")) {
					
					int period = indicatorListJs.get(indexOrder).getAsJsonObject().get("period").getAsInt();
					int buyIndex = indicatorListJs.get(indexOrder).getAsJsonObject().get("buyIndex").getAsInt();
					int sellIndex = indicatorListJs.get(indexOrder).getAsJsonObject().get("sellIndex").getAsInt();
					
					indicatorCalcer[i] = new CommodityChannelIndex(period, buyIndex, sellIndex, crypt, exchange, coin, base, interval);
				}
				else if(indicator.equals("gdCross")) {
					
					int longd = indicatorListJs.get(indexOrder).getAsJsonObject().get("longD").getAsInt();
					int shortd = indicatorListJs.get(indexOrder).getAsJsonObject().get("shortD").getAsInt();
					int mT = indicatorListJs.get(indexOrder).getAsJsonObject().get("mT").getAsInt();
					
					indicatorCalcer[i] = new gdCross(longd, shortd, mT, crypt, exchange, coin, base, interval);
				}
				else if(indicator.equals("gdVCross")) {
					
					int longd = indicatorListJs.get(indexOrder).getAsJsonObject().get("longD").getAsInt();
					int shortd = indicatorListJs.get(indexOrder).getAsJsonObject().get("shortD").getAsInt();
					int mT = indicatorListJs.get(indexOrder).getAsJsonObject().get("mT").getAsInt();
					
					indicatorCalcer[i] = new gdVCross(longd, shortd, mT, crypt, exchange, coin, base, interval);
				}
				else if(indicator.equals("MFI")) {
					
					int period = indicatorListJs.get(indexOrder).getAsJsonObject().get("period").getAsInt();
					int buyIndex = indicatorListJs.get(indexOrder).getAsJsonObject().get("buyIndex").getAsInt();
					int sellIndex = indicatorListJs.get(indexOrder).getAsJsonObject().get("sellIndex").getAsInt();
					
					indicatorCalcer[i] = new MFI(period, buyIndex, sellIndex, crypt, exchange, coin, base, interval);
				}
				else if(indicator.equals("StochOsc")) {
					
					int n = indicatorListJs.get(indexOrder).getAsJsonObject().get("n").getAsInt();
					int m = indicatorListJs.get(indexOrder).getAsJsonObject().get("m").getAsInt();
					int t = indicatorListJs.get(indexOrder).getAsJsonObject().get("t").getAsInt();
					
					indicatorCalcer[i] = new StochasticOsillator(n, m, t, crypt, exchange, coin, base, interval);
				}
				else if(indicator.equals("VolumeRatio")) {
					
					int period = indicatorListJs.get(indexOrder).getAsJsonObject().get("period").getAsInt();
					int buyIndex = indicatorListJs.get(indexOrder).getAsJsonObject().get("buyIndex").getAsInt();
					int sellIndex = indicatorListJs.get(indexOrder).getAsJsonObject().get("sellIndex").getAsInt();
					
					indicatorCalcer[i] = new VolumeRatio(period, buyIndex, sellIndex, crypt, exchange, coin, base, interval);
				}
				else if(indicator.equals("RSI")) {
					
					int period = indicatorListJs.get(indexOrder).getAsJsonObject().get("period").getAsInt();
					int buyIndex = indicatorListJs.get(indexOrder).getAsJsonObject().get("buyIndex").getAsInt();
					int sellIndex = indicatorListJs.get(indexOrder).getAsJsonObject().get("sellIndex").getAsInt();
					
					indicatorCalcer[i] = new RSI(period, buyIndex, sellIndex, crypt, exchange, coin, base, interval);
				}
			} catch(Exception e) {
				///////////////////////////////////ERROR//////////////////////////////////////////
				// Sleep or Terminate?
				// 초기 생성 오류 -> 기다리는걸 추천... 
				System.out.println("error ! : " + LocalDate.now());
				if(errorHandling == 1) {
					// 대기
					try {
					Thread.sleep(interval/10*1000);
					i--;
					continue;
					}
					catch(Exception e3) {
						System.out.println("first initializing error -> sleep -> erro");
					}
				}
				else {
					// 종료
					// timer를 실행하기 이전에 그냥 리턴해버리므로 걍 봇이 종료되는거임
					// DB 상태 0으로 전환 , trans_log는 업데이트 ㄴㄴ
					String sql = String.format("UPDATE trade SET status=0 WHERE email = \"%s\" and bot_name = \"%s\" ", email, botName);
					try {
						useDB.Query(sql, "insert");
					} catch (SQLException se) {
						se.printStackTrace();			
					}		
				
					// ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ봇 종료 알람ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ
					String content = LocalDateTime.now() + "\n보라봇 " + botName+ " 이 초기 오류로 종료되었습니다.";
					String subject = "보라봇 " + botName + " 종료 알람";
					SendMail.sendEmail(email, subject, content);
					return;
				}
				
			}
		}
		useDB.clean();
		
		//ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ//
		// 												스레드 부분!
		Timer timer = new Timer();
		
		TimerTask task = new TimerTask() {
			@Override
			public void run() {
				
				int trigger = 1;
				
				DB useDB = new DB();
				
				// 시간 체크 //
				LocalDateTime now = LocalDateTime.now();
				System.out.println("오차? : " + now);
				LocalDateTime deadDay = LocalDateTime.parse(endDate);
				if(now.isAfter(deadDay)) {
					//종료
					
					//---moduel---//
					double ticker = exAPIobj.getTicker(coin, base);
					double numOfNowCoin = exAPIobj.getBalance(coin);
					double balanceOfNow = exAPIobj.getBalance(base);
					double total = numOfNowCoin * ticker + balanceOfNow;
					
					String sql = String.format("UPDATE trade SET status=0, last_asset = %s, last_coin_number = %s, last_balance = %s WHERE email = \"%s\" and bot_name = \"%s\" ", total, numOfNowCoin, balanceOfNow, email, botName);
					try {
						useDB.Query(sql, "insert");
					} catch (SQLException e) {
						e.printStackTrace();			
					}		
					
					//---moduel---//
					
					// ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ봇 종료 알람ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ
					String content = LocalDateTime.now() + "\n보라봇 " + botName+ " 이 거래기간 만료로 종료되었습니다.";
					String subject = "보라봇 " + botName + " 종료 알람";
					SendMail.sendEmail(email, subject, content);
					
					System.out.println("trade is done : day expired");
					timer.cancel();this.cancel();
					trigger = -1;
				}
				
				// status 체크 //
				
				String tradeSQL = String.format("SELECT status FROM trade WHERE email = \"%s\" and bot_name = \"%s\" ", email, botName);
				String nowStatus="";
				try {
					ResultSet rsTemp = useDB.Query(tradeSQL, "select");
					if(rsTemp.next()) {
						nowStatus = rsTemp.getString(1);
					}
				} catch(Exception e) {
					e.printStackTrace();
				}
			
				if(nowStatus.equals("0")) {
					//종료 
					
					//---moduel---//
					double ticker = exAPIobj.getTicker(coin, base);
					double numOfNowCoin = exAPIobj.getBalance(coin);
					double balanceOfNow = exAPIobj.getBalance(base);
					double total = numOfNowCoin * ticker + balanceOfNow;
					
					String sql = String.format("UPDATE trade SET status=0, last_asset = %s, last_coin_number = %s, last_balance = %s WHERE email = \"%s\" and bot_name = \"%s\" ", total, numOfNowCoin, balanceOfNow, email, botName);
					try {
						useDB.Query(sql, "insert");
					} catch (SQLException e) {
						e.printStackTrace();			
					}		
					
					//---moduel---//
					
					// ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ봇 종료 알람ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ
					String content = LocalDateTime.now() + "\n보라봇 " + botName+ " 이 사용자 선택으로 종료되었습니다.";
					String subject = "보라봇 " + botName + " 종료 알람";
					SendMail.sendEmail(email, subject, content);
					
					System.out.println("trade is done : teminated by forced");
					timer.cancel();
					trigger=-1;
				}
				
				//1차관문 : DB에 상태가 진행중이거나, 날짜가 아직 안지났거나
				if(trigger == 1) {
					double fin;
					
					try {
						fin = getFinalDetermin(toPostfix(indicatorCalcer, expList, weightList));
						System.out.println("result : " + fin);
						
					}
					catch(Exception e) {
						//ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡCW or ExchangeAPI err알람ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ//
						//종료 ? 컨티뉴?
						System.out.println("error ! : " + LocalDate.now());
						System.out.println("FianlDetermin을 구하는 도중 일어난 에러! - cw나 exapi문제일 확률!");
						e.printStackTrace();
						fin = -1; // meaningless
						
						if(errorHandling == 1) {
							System.out.println("FinalDetermin error ! - waiting!");
							try {
								Thread.sleep(interval/10 * 1000);
							}catch(Exception e2) {
								System.out.println("스레드 슬립 에러 ! ");
							}
							trigger = -1;
							run();
							return;
						}
						else if(errorHandling == 0) {
							// 종료
							// sql update status = 0;
							
							//---moduel---//
							double ticker = exAPIobj.getTicker(coin, base);
							double numOfNowCoin = exAPIobj.getBalance(coin);
							double balanceOfNow = exAPIobj.getBalance(base);
							double total = numOfNowCoin * ticker + balanceOfNow;
							
							String sql = String.format("UPDATE trade SET status=0, last_asset = %s, last_coin_number = %s, last_balance = %s WHERE email = \"%s\" and bot_name = \"%s\" ", total, numOfNowCoin, balanceOfNow, email, botName);
							try {
								useDB.Query(sql, "insert");
							} catch (SQLException se) {
								se.printStackTrace();			
							}		
							
							//---moduel---//
							
							// ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ봇 종료 알람ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ
							String content = LocalDateTime.now() + "\n보라봇 " + botName+ " 이 거래 중 데이터 api 오류로 종료되었습니다.";
							String subject = "보라봇 " + botName + " 종료 알람";
							SendMail.sendEmail(email, subject, content);
							
							trigger=-1; // timer cancel을 해도 최초 1회는 실행되므로, 그걸 막기 위해 트리거를 별도 설정
							fin = -1; // meaningless
							timer.cancel(); // 이후에 작업 X
						}
					}
					
					
					// 2차관문 : FinalDetermin을 구하는데 에러가 나면 이 트리거로 뒤에 코드는 실행 X
					if(trigger == 1) {
						if(fin >= buyCriteria) {
							System.out.println("buy!");
							
							double numOfSalingCoin;
							
							if(buyingSetting.equals("buyAll")) {
								
								numOfSalingCoin = buyAll(exAPIobj);
							}
							else if(buyingSetting.equals("buyCertainPrice")) {
								
								numOfSalingCoin = buyCertainPrice(exAPIobj, priceBuyUnit);
							}
							else {
								
								numOfSalingCoin = buyCertainNum(exAPIobj, numBuyUnit);
							}
						
							numOfSalingCoin = shapingnumOfSalingCoin(numOfSalingCoin);
							
							//exAPIobj.buyCoin(coin, base, numOfSalingCoin+"");
							
							double ticker = exAPIobj.getTicker(coin, base);
							double numOfNowCoin = exAPIobj.getBalance(coin);
							double balanceOfNow = exAPIobj.getBalance(base);
							double total = numOfNowCoin * ticker + balanceOfNow;
							
							String currentTime = LocalDateTime.now().toString();
							
							String sql = String.format("INSERT INTO trans_log VALUES(\"%s\", \"%s\", \"%s\", \"%s\", %s, \"%s\", \"%s\", %s, %s, %s, %s)", email, botName, exchange, currentTime, 1, coin+base, ticker, numOfSalingCoin, total, balanceOfNow, numOfNowCoin );
							System.out.println(sql);
							try {
								useDB.Query(sql, "insert");
							} catch (SQLException e) {
								e.printStackTrace();			
							}		
							
							//ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡsendAlarm : 구매 ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ//
							//ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ알람내용 디비에 저장ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ//
							String subject = "보라봇 " + botName + " 구매 알람!";
							String content = String.format("%s \n 보라봇이 코인 %s 을 %s개 시장가로 매수주문 보냈습니다. 현재 코인 : %s , 현재 잔액 : %s", LocalDateTime.now(), coin, numOfSalingCoin, numOfNowCoin, balanceOfNow);
							SendMail.sendEmail(email, subject, content);
							
						}
						else if(fin <= sellCriteria) { // 매도 시그널!
							System.out.println("sell!");
		
							double numOfSalingCoin;
							
							if(sellingSetting.equals("sellAll")) {
								
								numOfSalingCoin = sellAll(exAPIobj);
							}
							else if(sellingSetting.equals("sellCertainPrice")) {
								
								numOfSalingCoin = sellCertainPrice(exAPIobj, priceSellUnit);
							}
							else {
								
								numOfSalingCoin = sellCertainNum(exAPIobj, numSellUnit);
							}
							
							numOfSalingCoin = shapingnumOfSalingCoin(numOfSalingCoin);
							
							//exAPIobj.sellCoin(coin, base, numOfSalingCoin+"");
							
							double ticker = exAPIobj.getTicker(coin, base);
							double numOfNowCoin = exAPIobj.getBalance(coin);
							double balanceOfNow = exAPIobj.getBalance(base);
							double total = numOfNowCoin * ticker + balanceOfNow;
							
							String currentTime = LocalDateTime.now().toString();
							
							String sql = String.format("INSERT INTO trans_log VALUES(\"%s\", \"%s\", \"%s\", \"%s\", %s, \"%s\", \"%s\", %s, %s, %s, %s)", email, botName, exchange, currentTime, -1, coin+base, ticker, numOfSalingCoin, total, balanceOfNow, numOfNowCoin );
							System.out.println(sql);
							try {
								useDB.Query(sql, "insert");
							} catch (SQLException e) {
								e.printStackTrace();			
							}		
							
							//ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡsendAlarm : 판매 ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ//
							//ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ알람내용 디비에 저장ㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡㅡ//
							String subject = "보라봇 " + botName + " 판매 알람!";
							String content = String.format("%s \n 보라봇이 코인 %s 을 %s개 시장가로 매도주문을 보냈습니다. 현재 코인 : %s , 현재 잔액 : %s", LocalDateTime.now(), coin, numOfSalingCoin, numOfNowCoin, balanceOfNow);
							SendMail.sendEmail(email, subject, content);
							}
						else {
							System.out.println("wait!");
							double ticker = exAPIobj.getTicker(coin, base);
							double numOfNowCoin = exAPIobj.getBalance(coin);
							double balanceOfNow = exAPIobj.getBalance(base);
							double total = numOfNowCoin * ticker + balanceOfNow;
							String currentTime = LocalDateTime.now().toString();
						
							String sql = String.format("INSERT INTO trans_log VALUES(\"%s\", \"%s\", \"%s\", \"%s\", %s, \"%s\", \"%s\", %s, %s, %s, %s)", email, botName, exchange, currentTime, 0, coin+base, ticker, 0, total, balanceOfNow, numOfNowCoin );
							System.out.println(sql);
							try {
								useDB.Query(sql, "insert");
							} catch (SQLException e) {
								e.printStackTrace();			
							}									
						}
						
					System.out.println("\n");
					
					}
				}
				useDB.clean();
			}
		};
		
		// 거래 시작!!
		// till the done..~
		// 거래 시간을 표준시 분단위로 맞춰서 진행할 지..?
		// ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
		// service.scheduleAtFixedRate(command, initialDelay, period, unit)
		// timer.schedule(task, 0, interval*1000);
		timer.scheduleAtFixedRate(task, 1000, interval*1000);

		
		
		// 후처리 ㄱㄱ

	}
	
	// 8자리로
	private static double shapingnumOfSalingCoin(double numOfSalingCoin) {
		
		double ret = Double.parseDouble(String.format("%.8f",numOfSalingCoin));
		System.out.println("shaped : " + ret);
		
		return ret;
	}
	
	// 올인선택
	// 다 사버려
	private static double buyAll(exAPI api) {
		
		return api.getBalance(base) / api.getTicker(coin, base);
	}
	
	// 특정 가격 만큼 산다고 정하면
	// 이 함수
	private static double buyCertainPrice(exAPI api, double value) {
		
		if(api.getBalance(base) > value) {
			return value / api.getTicker(coin, base);
		}
		else {
			return 0;
		}
	}
	
	// 특정 갯수 만큼 삼
	// 이 함수
	private static double buyCertainNum(exAPI api, double value) {		
		if(api.getBalance(base) / api.getTicker(coin, base) > value) {
			return value;
		}
		else {
			return 0;
		}
	}
	
	private static double sellAll(exAPI api) {
		
		return api.getBalance(coin);
	}
	
	private static double sellCertainPrice(exAPI api, double value) {
		
		if(value / api.getTicker(coin, base) <= api.getBalance(coin)) {
			return value / api.getTicker(coin, base);
		}
		else {
			return 0;
		}
	}
	
	private static double sellCertainNum(exAPI api, double value) {		
		
		if(api.getBalance(coin) >= value) {
			return value;
		}
		else {
			return 0;
		}
	}
	
	private void IOC() {
		
		// uuid받아와서
		// 바로 취소때려버리기
	}
	
	private double getFinalDetermin(Stack<String> post) {
		
		// 현재 스택이 abc++식으로 되어있으므로
		// 뒤집어서 ++cba식으로 바꿔준다
		// pop을 하면 맨 뒤(a)부터 빠져나오기 때문!
		Stack<String> tempStk = new Stack<String>();
		
		int postSize = post.size();
		for(int i = 0; i < postSize; i++) {
			tempStk.push(post.pop());
		}
		
		// 계산스택
		// 연산자를 만나면 두개를 팝해서 연산한 뒤 다시 푸쉬
		Stack<String> calStk = new Stack<String>();
		int size = tempStk.size();
		for(int i = 0; i < size; i++){
			
			String poped = tempStk.pop();
			
			if(poped.equals("or")) {
				
				String temp1 = calStk.pop();
				String temp2 = calStk.pop();
				
				int ret = Integer.parseInt(temp1) + Integer.parseInt(temp2);
				calStk.push(ret+"");
			}
			else if(poped.equals("and")) {
				String temp1 = calStk.pop();
				String temp2 = calStk.pop();
				
				int ret = Integer.parseInt(temp1) * Integer.parseInt(temp2);
				calStk.push(ret+"");
			}
			else {
				calStk.push(poped);
			}
		}
		//printStack(calStk);
		return Double.parseDouble(calStk.pop());
	}
	
	private Stack<String> toPostfix (calcIndicator[] indicatorList, String[] expList, int[] weightList) throws Exception{
		
		// 피연산자 리스트
		// 연산자 리스트
		// 가 있다. 그러면 연산자 리스트는 피연산자 리스트보다 항상 1개가 작을 수 밖에 없다.
		// 피연산자는 후위표기스택에 넣고, 연산자는 따로 수식스택에 넣고 계산한다.
		
		Stack<String> postStk = new Stack<String>();
		Stack<String> expStk = new Stack<String>();
		
		for(int i = 0; i < expList.length; i++) {
			try {
				String tempStr = indicatorList[i].getDeterminConstant()*weightList[i]+"";
				System.out.println(indicatorList[i] + " : " + tempStr);
				postStk.push(tempStr);
				
			} catch(Exception cE) {
				throw new Exception();
			}
			
			if(expStk.isEmpty()) {
				expStk.push(expList[i]);
			}
			else {
				if(expList[i].equals("and")) {

					if(expStk.lastElement().equals("and")) {
						postStk.push(expStk.pop());
					}
					expStk.push(expList[i]);

				}
				else if(expList[i].equals("or")) {
					
					while(!expStk.empty()) {
						postStk.push(expStk.pop());
					}
					expStk.push(expList[i]);
				}
			}
		}
		String tempStr = indicatorList[indicatorList.length-1].getDeterminConstant()*weightList[weightList.length-1]+"";
		System.out.println(indicatorList[indicatorList.length-1] + " : " + tempStr);
		postStk.push(tempStr);
		
		while(!expStk.isEmpty()) {
			postStk.push(expStk.pop());
		}
		//printStack(postStk);
		return postStk;
	}
	
	
	public static void printStack(Stack<String> stk) {
		System.out.println("------");
		for(int i = 0; i < stk.size(); i++) {
			System.out.println(stk.get(stk.size()-i-1));
		}
		System.out.println("------");
	}
}
