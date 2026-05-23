# OSBot forum thread — full extraction

Source: https://osbot.org/forum/topic/153397-osbots-mouse-movement-is-easily-detected/
Total posts: 151

---

## [p1] asdttt — 2019-04-07T01:13:16Z

Made an earlier post about how all OSBot scripts seem detected, and posted some findings relating to the banrates of changing various things such as timings, clickspeed, movement, and mouse DPI. In testing, I found that almost 100% of all OSBot's mouse movements follow very simplistic patterns that are picked up very quickly by Jagex's anti-bot. This goes for ALL other client's I've tested, all containing some flaw within their mouse movement, whether it be [Other-Bot-Client]'s flawed inaccurate spoofed mouse movement, or [Other-Bot-Client]'s consistency. I've reported these flaws to the developers of OSBot already, but was not met with confirmation on whether or not they'll do anything about it. Possibly because they're still under the illusion that mouse movement doesn't play a big role in detection. So here's a topic to prove just that.

 

First off, let me start by showing that Jagex certainly does record mouse movement: 

https://github.com/zeruth/runescape-client/blob/master/src/MouseRecorder.java#L40

This shows the frequency of their collection. 50MS ticks, which is equivalent to 20 times a second. 

Now you could say.. But isn't 50 MS not enough to accurately depict mouse movement? And that is true to some extent, but it's more then enough data to analyse in order to find flaws or patterns. 

Here's what it looks like to move a mouse on a 50MS tick-rate: https://i.gyazo.com/4eb9de90c1c8a60959e874fb24488ab3.mp4

A common argument may be that collecting mouse movement is an absurd amount of data, but.. They combine the integers into mostly a 2 byte for small/medium, and larger a 3 byte or 4 byte and save/send it as that.  That means  they can store around 250,000-500,000 x/y captures per 1mb. That translates to around 3.4 HOURS of constant mouse movement data capture per user. That data would obviously build up over-time, but IMO Jagex most likely clears this data either every ban-wave, or every week. Which wouldn't really be that much. You could also compress these integers an insane amount due to how primitive the encoding would be.

They also only send movements, not equal, or zero movements: https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3330 - Although, they still keep track of those equal/zero movements: https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3332

 

So we can see they record the data locally, but do they send it to the server? The answer is, yes. Here's proof of that (Annd they send a loot more then just that...):

https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3307

And here's them encoding the difference between mouse x/y movements into a 2 byte integer and appending it to their packet buffer (Only medium speed movements under about 31 pixel per 50MS):

https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3342

And here's them encoding movements into a 3 byte integer and appending it to their packet buffer (var10 = mouseY * 765 + mouseX):

https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3345

And here's them encoding movements into a 4 byte integer and appending it to their packet buffer (var10 = mouseY * 765 + mouseX)::

https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3348

They also can detect when you move your mouse outside of the screen, and how many ticks (Ticks are capped of course):

https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3324

 

Reconstructing their mouse collection:

||) Equal, or zero movements are tracked by ticks. If you don't move your mouse for 30 ticks, they'll know. They most likely use this for multiple purposes, the biggest being the ability to figure out the entire mouse movement rather then just bits of it. 

1) Movement of the mouse is tracked, smalls/medium movements exactly by this (Only medium/small per-tick movement difference under about 30 in length)

int yDiff = (recordedY - previouslyRecordedYMove); 

int xDiff = (recordedX - previouslyRecordedXMove);

handler.packetBuffer.putShort(yDiff + (idleIndexesPassed<< 12) + (xDiff << 6));

idleIndexesPassed = 0;

movementIndex = the indexes skipped before finding a mouse move in the X/Y mouse recorder. Used to track time between mouse movmenets. 

 

2) Larger movements that are made in less then 8 ticks of "idle" mouse: (Actual location sent)

int var10 = (recordedY * 765 + recordedX);

handler.packetBuffer.put24bitInt((idleIndexesPassed << 19) + var10 + 8388608);

idleIndexesPassed = 0;


 

3) Large movements that are made 8+ ticks from being "idle"  (Actual location sent)

int var10 = (recordedY * 765 + recordedX);

var14.packetBuffer.putInt((idleIndexesPassed << 19) + var10 + -1073741824);

idleIndexesPassed  = 0;﻿

(Don't mind the -1073741824 or other random numbers. Java doesn't support primitive unsigned numbers, so you have to use hacky stuff to compress your integer sizes)

 

The majority of OSBot's movements would fall under #1's logging. The others are just for larger mouse movements (in terms of last X/Y -> new X/Y). Why do they multiple Y by 765? Because they've capped the X axis from exceeding 764 therefor they can easily mathematically combine the two integers for saving resources,  then de-couple them whenever they want. 

 

So what does all this have to do with OSBot's mouse movement? Well, I performed a basic test to grab the mouse movement delta's between every 50MS tick (Just as Jagex does) and found 100% consistency among certain parts of their mouse movement patterns:

OSBot's mouse movement: https://pastebin.com/AJn2NC31

My own mouse movement: https://pastebin.com/vnGtX16z

Right away you should notice many flaws inside OSBot's mouse sample. As you can clearly see, OSBot repeats ONLY 4-5 at the end of movements, AND at the last few deltas at the end of the movement, it goes from Lower, Bigger, Lower, This seems to be the case with virtually all mouse movements over 4-5 pixels large made by OSBot. 

So if I'm able to detect this flawed mouse movement in a matter of seconds with basic math, then so can Jagex? This would also explain why tasks requiring massive amounts of mouse movement, such as agility, have much higher ban-rates vs something like fighting, or AFK tasks. 

 

 Does this mean this is the only detection method banning OSBot? Absolutely not. However, in my experience, I've yet to be banned by using OSBot as an API for everything BUT mouse movement, or camera movement (Of course with a lot more human-like behavior sprinkled on top of the scripts). This is purely based on only a little more then a week of botting without a ban, so it's possible I'm not bypassing, but simply delaying my ban. Hell, it's possible I was detected the second my script first started and now i'm just riding a monthly ban wave. Still beats a daily ban wave though... 

 

 Edited April 9, 2019 by asdttt
Fixed some missing info

---

## [p1] asdttt — 2019-04-07T01:18:58Z

Here's a utility to sample mouse deltas:

public class MouseDebugger {

    private int tick = 0;

    private int lastMouseX = 0;

    private int lastMouseY = 0;

    private boolean endedMovement = true;

    private int noMoveTicks = 0;

    private Script script;

    public MouseDebugger(Script script) {
        this.script = script;
    }

    //Run this on a 50MS tick, or whatever you want to sample on
    public void tick() {

        final Point mosePosition = script.mouse.getPosition();

        if (lastMouseX != mosePosition.getX() || lastMouseY != mosePosition.getY()) {

            double actualDistance = Math.sqrt((lastMouseY - mosePosition.getY()) * (lastMouseY - mosePosition.getY())
                    + (lastMouseX - mosePosition.getX()) * (lastMouseX - mosePosition.getX()));


            if (actualDistance > 0 && actualDistance != Double.NaN) {
                script.log("Delta: " + actualDistance + " Tick: " + tick);
            }

            endedMovement = false;

            noMoveTicks = 0;

        } else {

            ++noMoveTicks;

            //Change according to how long you pause until making another mouse movement
            if (noMoveTicks >= 6) {

                if (!endedMovement)
                    script.log("--Mouse movement ended");

                endedMovement = true;
            }
        }

        lastMouseX = (int) mosePosition.getX();
        lastMouseY = (int) mosePosition.getY();

        ++tick;

    }

}

 Edited April 7, 2019 by asdttt

---

## [p1] nvrsince — 2019-04-07T02:09:02Z

I highly doubt anything will be done about this unless they're willing to hire you

---

## [p1] Night — 2019-04-07T02:48:31Z

I didn't read the post, but you might want to be careful about posting Jagex's code on your github. They tend not to like people doing things like that (redistributing copy-righted code).

---

## [p1] THS — 2019-04-07T03:03:03Z

I'm willing to entertain this and I don't even know what I'm looking at.

Any script kiddies wanna give me the juice?

---

## [p1] asdttt — 2019-04-07T04:06:00Z

On 4/7/2019 at 5:03 AM, THS said:

I'm willing to entertain this and I don't even know what I'm looking at.

Any script kiddies wanna give me the juice? 

Tl;Dr: OSBot's API to move the mouse is very flawed, and the appearance of it looking human is only an illusion. Even moving the mouse randomly would produce the same flaws. Only thing that doesn't appear to have flaws is moving the mouse outside the screen.

---

## [p1] dragonite3000 — 2019-04-07T05:37:00Z

someone give this man a job

---

## [p1] Patrick — 2019-04-07T06:45:04Z

Not only did I already tell you I believe mouse movement can be used for detection - all be it only a very small part of the system -, I also told you it's something we're interested in changing and are discussing. From months of testing I can confirm that you can bot without getting banned when botting 4+ hours almost everyday, when only using the OSBot API.

---

## [p1] Tesh — 2019-04-07T06:55:00Z

The fact of the matter is, all bots become detected at some point and there is no way of counteracting that. The way I look at it is, you're going to get banned eventually, so only use accounts you're willing to lose.

 Edited April 7, 2019 by Tesh

---

## [p1] asdttt — 2019-04-07T07:14:26Z

On 4/7/2019 at 8:45 AM, Patrick said:

Not only did I already tell you I believe mouse movement can be used for detection - all be it only a very small part of the system -, I also told you it's something we're interested in changing and are discussing. From months of testing I can confirm that you can bot without getting banned when botting 4+ hours almost everyday, when only using the OSBot API.

You said, and I quote, "I would have to discuss with the others about changing the mouse movement". You never confirmed whether the mouse movement would be improved..? I figured you and the other devs simply came to the conclusion that the mouse movement was fine, because.. What else was I suppose to believe...?

Maybe your testing is outdated. I couldn't find a single miner bot on this entire form, paid or free, that bypassed 3 hours of mining. I had to make my own script to bypass, and only until I stopped using some of OSBot's API was I able to successfully bypass and still to this day I've yet to be banned. Even agility training is bypassing now, which is a very high banrate due to the amount of mouse movement required in most courses. It's got a lot of human-like behavior too like not being consistent, taking tiny breaks - like emulating a human moving the mouse off screen to click something in another window, ect.

And yeah, it doesn't account for the majority of their detection's, but it still can and will lead to a ban - which in my case, was the only reason I was actually being banned. 

 Edited April 7, 2019 by asdttt

---

## [p1] asdttt — 2019-04-07T07:23:29Z

On 4/7/2019 at 8:55 AM, Tesh said:

The fact of the matter is, all bots become detected at some point and there is no way of counteracting that. The way I look at it is, you're going to get banned eventually, so only use accounts you're willing to lose.

This is true, but as RS's history goes, there is always a way around it. Yeah, they can patch it, but that patch is a small piece of tape on a massive industry. It also generally takes multiple months to years for them to fix too which is more then enough time to make a lot of $$.

---

## [p1] asdttt — 2019-04-07T08:44:55Z

On 4/7/2019 at 10:10 AM, Malcolm said:

Can we be real here for a minute?

We are talking about a bot...a macro to do an automated repetitive task.

The developers do a fantastic job of supplying us with the tools that we need to give customers a product.

I can also tell you that I have success with only using the OSBOT API.

I do have some methods to do some things differently but for the most part the API is very good at what it does.

 

There is no reason to bash the API or bash the mouse movements. The days where we could bot for weeks without a ban are gone. Jagex have adapted and their methods of detection are actually decent.

Can you beat it? Who knows.

Odds are if you bot for 16hrs/day with your own personal API and mouse you're still going to get banned eventually.

Who knows, maybe Jagex figured out that you've done something unique and are just gathering information on your new advanced API and mouse movements 

 

Expand

"We are talking about a bot...a macro to do an automated repetitive task." - Yeah "repetitive task". You mean the very task required for literally any skill on OSRS? You think mining iron, banking, mining iron, banking without botting isn't repetitive....? What's your arugment lmao

I think you're completely misunderstanding me and instead think I'm attacking OSBot... I'm not, I'm actually trying to improve OSBot. If I was bashing it, I sure as hell wouldn't still be using it as there are many alternatives. 

What I said and provided is undenyable proof. If you honestly believe that the samples created from using OSBot's mouse isn't very easy to detect then you're delusional and need to seek help. 

It's not about being advanced.. The current movement is advanced, and even has deviation in it's mouse movement.. However, the developers who made it probably were more focused on making it LOOK human, rather then trying to see if they themselves could pickup on the pattern. 

So why make this post? To bring attention to this issue... As simple as that. I've provided more then enough evidence/samples that Patrick should be able to make the necessary changes within OSBot to reduce, or remove this highly flawed mouse movement. 

Why did I "bash" on using 100% of OSBots API? Well for starters, I wasn't TRYING to, I was simply providing that I myself now suddenly bypass after changing the mouse moving functionality... It was an example to further my claim, which IMO, it has. 

 Edited April 7, 2019 by asdttt

---

## [p1] Protoprize — 2019-04-07T08:52:52Z

Do we forget to hover over WC?

 

On a serious note, osbot is fine as is. I'm gonna be honest and just say it. I broke the one rule everyone says not to. I bot my main.... and I started 2 months ago with no ban still because I'm botting smart ?‍♂️.

Of course, in the end, changing osbot's mouse api would change the ban rates of using the client. But right now, that is a small priority, mostly due to the fact that Jagex is making their bot detection methods more and more obvious (and osbot needs to adapt to them first and foremost). The mouse is not the reason why people get banned. 

The reason for getting banned is sometimes just luck, but most of the time, it's stupidity. If you bot smart and not hard, you won't have issues.

---

## [p1] asdttt — 2019-04-07T08:57:15Z

On 4/7/2019 at 10:52 AM, Protoprize said:

Do we forget to hover over WC?

 

On a serious note, osbot is fine as is. I'm gonna be honest and just say it. I broke the one rule everyone says not to. I bot my main.... and I started 2 months ago with no ban still because I'm botting smart ?‍♂️.

Of course, in the end, changing osbot's mouse api would change the ban rates of using the client. But right now, that is a small priority, mostly due to the fact that Jagex is making their bot detection methods more and more obvious (and osbot needs to adapt to them first and foremost). The mouse is not the reason why people get banned. 

The reason for getting banned is sometimes just luck, but most of the time, it's stupidity. If you bot smart and not hard, you won't have issues.

There's no single reason why we're being banned. It's not as easy as that. 

This mouse movement is clearly one of many of these detection methods though.. As you can clearly see, it's very different then human mouse movement. It's very close to being there though.

---

## [p1] Tesh — 2019-04-07T09:03:25Z

From personal experience, ive been botting for years, and I mean years. Ive only ever gotten one account banned, due to my own stupidity. Been using OSBot for a little while now, not banned as of yet and no ones called me out for botting.

---

## [p2] asdttt — 2019-04-07T09:14:30Z

On 4/7/2019 at 11:01 AM, Malcolm said:

At what point in time did I deny any sort of detection claims? I didn't. So clearly I don't need to seek help 

If I recall correctly @Alek had mentioned something about the client itself being detectable although I cannot find the exact post for it.

It's been known for quite some time that the injection client is detectable which goes far beyond the mouse.

My point is that the mouse is a fraction of the bigger picture.

Look man. If you don't believe anything I'm saying about bypassing, then just leave the topic. All your doing is attacking me with assumptions, which I understand, i too would be suspicious of someone who claims to have magically bypassed bans by simply using alternative mouse movement. I don't blame you. 

And I understand your point that mouse movement isn't the biggest factor, nowhere did I claim it is actually. For me, it made the difference between bot freedom and daily bans, but maybe I'm just a lying cunt wasting everyone's time. I get it. 

But here I've served you proof of this flawed mouse movement that is absolutely picked up by heuristics. Now you can know for certainty that they have one solid link to detect OSBot, and now the dev's will be able to target it and hopefully fix it (I actually provided a possible solution, but whether or not he think's it'll work out for OSBot is another story). 

 

Imho, injection client isn't detected as a bot. Jagex has said themselves using third party clients is perfectly fine, wont lead to a ban, and their code shows no sign of code that would be used to detect whether a user is using a properly created bot client,  nor do they have any streamlined classloading that could inject such a thing during runtime. Now, do they detect whether you're using an unofficial client? Possibly?

 Edited April 7, 2019 by asdttt

---

## [p2] asdttt — 2019-04-07T09:50:43Z

On 4/7/2019 at 11:34 AM, Malcolm said:

They cannot detect what client you are using. They can tell that you are not using an official OSRS client. As far as I'm aware that's how that goes.

I don't really feel like I'm attacking you and that is certainly not what I am trying to do however I was a little sarcastic I'll admit.

If you want to get into attacks you can try and recall this. It was a little funny but you essentially attacked every scripter here by calling their scripts shitty.

 

There are soo many factors that go into Jagex's bot detection and we certainly don't know how they really do it.

@Patrick already admitted to you that he believes mouse movements are detected.

He also said its a small part of the system which I 100% agree with him on.

 

This isn't like using the API and using the built in mouse is praying for a conviction. Bot smart. Don't bot for 16 hours a day.

If I want to keep an account I have no problem with botting that account and not getting it banned.

 

 

 

Expand

I mean that "attack" I said was very true...? If your script is basic as hell and does nothing to seem human, it's shit. As easy as that. 

 

Anyways, I just noticed something strange inside RS's code. Mind you, it's obfuscated so I really haven't fully drawn out what exactly this does, but here it is:

https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L4315
Weird right? Maybe I'm mistaking it with some dynamic classloader, but from what I believe I'm looking at is a means to load a class and read some details about it. Could they possibly be using this to figure out overridden classes? Or checking to see if certain things exist that shouldn't? 

Then they add these details to a list: https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L4418

Then here they are encoding that same object into their packet buffer: https://github.com/zeruth/runescape-client/blob/master/src/class21.java#L59

Then they ship it? https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3273

 

That's really odd, although maybe it has something todo with actual ingame mechanics and I just misinterpreted it. 

 Edited April 7, 2019 by asdttt

---

## [p2] asdttt — 2019-04-07T10:09:38Z

On 4/7/2019 at 11:58 AM, Malcolm said:

Did you really read through all of their code?

Also, do you really think Jagex would make their bot detection methods accessible to the public?

btw, this is what you had said in your OP on the other post.

 

Didn't say anything about if these scripts had human like movements or these scripts do whatever feature you think will help. Just flat out said they were all shit. Talk about an attack 

I mean Jagex used to detect bots very efficiently (for awhile until workarounds) using client based checks, so I wouldn't put it past them. This is the same company that managed to fuck up deadman mode and killed everyone. I mean the bot "nuke" was pretty much just them tricking us into using functions that would flag us. And like I've said probably a hundred times now, and I'm sure you have too; there's no single detection method. It's made up of who the hell knows how many detection methods.  This is what makes it even more difficult to pinpoint exactly how they detect us, because we're not all being detected by the same means. 

Who knows, maybe Jagex only executes client related checks if you actively collect resources for over X hours. They too know that a bypass will soon come, and they have done as much as possible to delay it. Maybe I haven't bypassed at all. Maybe I'm just on a larger banwave to give me the illusion that I'm coming closer to cracking the anti-cheat. Maybe they're just waiting for me to bot an excesses amount of time? 

And yeah you make a valid point, I did pretty much call everyone's scripts shitty. I didn't really mean EVERYONE as in literally everyone, just public scripts and the paid one I tried. So you got me there, and for that I apologize. But to be fair, there's a lot of shitty scripts here 

 

Edit: Man this class info collector/sender is really bothering me.. They literally don't use ANY of the code collected. The only use for this I can forsee is debugging.. 

Like check this out: https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L4390

Why do they need to check if the class could be found, and do absolutely nothing with it other then return whether it was successful + the data ONLY to the server...................? It has to be for debugging, or detection.. How else could this be explained wtf?

 Edited April 7, 2019 by asdttt

---

## [p2] IDontEB — 2019-04-07T10:34:31Z

On 4/7/2019 at 11:50 AM, asdttt said:

I mean that "attack" I said was very true...? If your script is basic as hell and does nothing to seem human, it's shit. As easy as that. 

 

Anyways, I just noticed something strange inside RS's code. Mind you, it's obfuscated so I really haven't fully drawn out what exactly this does, but here it is:

https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L4315
Weird right? Maybe I'm mistaking it with some dynamic classloader, but from what I believe I'm looking at is a means to load a class and read some details about it. Could they possibly be using this to figure out overridden classes? Or checking to see if certain things exist that shouldn't? 

Then they add these details to a list: https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L4418

Then here they are encoding that same object into their packet buffer: https://github.com/zeruth/runescape-client/blob/master/src/class21.java#L59

Then they ship it? https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3273

 

That's really odd, although maybe it has something todo with actual ingame mechanics and I just misinterpreted it. 

Expand

If I recall correctly, what you're looking at is Jagex's class checker. They are sent classes to check for from the server and reply back with having it or not. A friend of mine has logged a few of the classes they asked for and it seems to be classes related to the actual client, nothing external.

 

 
On 4/7/2019 at 11:34 AM, Malcolm said:

They cannot detect what client﻿ you are using. They can tell that you are not using an official OSRS client. As far as I'm aware that's how that goes.

I don't really feel like I'm attacking you and that is certainly not what I am trying to do however I was a little sarcastic I'll admit.

If you want to get into attacks you can try and recall this. It was a little funny but you essentially attacked every scripter here by calling their scripts shitty.

 

 

There are soo many factors that go into Jagex's bot detection and we certainly don't know how they really do it.

@Patrick already admitted to you that he believes mouse movements are detected.

He also said its a small part of the system which I 100% agree with him on.

 

This isn't like using the API and using the built in mouse is praying for a conviction. Bot smart. Don't bot for 16 hours a day.

If I want to keep an account I have no problem with botting that account and not getting it banned.

 

 

 

Expand

They can't detect what client you're using, but knowing whether you're using an official OSRS client or not isn't hard to spoof if you know what you're supposed to change. It doesn't matter whether the client is reflection or injection.

 Edited April 7, 2019 by IDontEB

---

## [p2] asdttt — 2019-04-07T10:37:01Z

On 4/7/2019 at 12:34 PM, IDontEB said:

If I recall correctly, what you're looking at is Jagex's class checker. They are sent classes to check for from the server and reply back with having it or not. A friend of mine has logged a few of the classes they asked for and it seems to be classes related to the actual client, nothing external.

Yeah it's the class checker. But it could also potentially be used to detect clients - although that's a pretty big stretch. I just thought it was odd, but now that I think about it, that's a good way to detect whether a client's oudated, or incompatible. 

Thanks for the info

---

## [p2] Imthabawse — 2019-04-07T12:22:03Z

getmouse.moveoutsidescreen

Winning! ?

---

## [p2] dreameo — 2019-04-07T12:36:20Z

I don't really see anything wrong with this. I think most people in this forum are very pessimistic about anti-ban. That's partly due to Alek's own beliefs that spread. But It's better to explore stuff and try it out. Who knows what works and what might not work. Yes, these same things have been tested but not all tests are the same and something new could be discovered.

So yeah, just do you and try out new ways of anti-ban.

 Edited April 7, 2019 by dreameo

---

## [p2] manko — 2019-04-07T12:47:05Z

On 4/7/2019 at 10:52 AM, Protoprize said:

Do we forget to hover over WC?

 

On a serious note, osbot is fine as is. I'm gonna be honest and just say it. I broke the one rule everyone says not to. I bot my main.... and I started 2 months ago with no ban still because I'm botting smart ?‍♂️.

Of course, in the end, changing osbot's mouse api would change the ban rates of using the client. But right now, that is a small priority, mostly due to the fact that Jagex is making their bot detection methods more and more obvious (and osbot needs to adapt to them first and foremost). The mouse is not the reason why people get banned. 

The reason for getting banned is sometimes just luck, but most of the time, it's stupidity. If you bot smart and not hard, you won't have issues.

im sorry botting smart is not part off it i made a new account did some hand leveling and some quests did 1 hour off mining then got some sleep next day account pem banned so its more than botting smart that stops you gets banned.

---

## [p2] Satire — 2019-04-07T14:02:03Z

On 4/7/2019 at 11:34 AM, Malcolm said:

They cannot detect what client you are using. They can tell that you are not using an official OSRS client. As far as I'm aware that's how that goes.

I don't really feel like I'm attacking you and that is certainly not what I am trying to do however I was a little sarcastic I'll admit.

If you want to get into attacks you can try and recall this. It was a little funny but you essentially attacked every scripter here by calling their scripts shitty.

 

There are soo many factors that go into Jagex's bot detection and we certainly don't know how they really do it.

@Patrick already admitted to you that he believes mouse movements are detected.

He also said its a small part of the system which I 100% agree with him on.

 

This isn't like using the API and using the built in mouse is praying for a conviction. Bot smart. Don't bot for 16 hours a day.

If I want to keep an account I have no problem with botting that account and not getting it banned.

 

 

 

Expand

They actually can. I saw a post  of a dude telling Weath he didn't bot. Weath then said "you were using a botting client, you were botting".  So I for one, think they can. The data could possibly be spoofed, but I reckon they have a way to be able to tell what clients you're using (from let's say official, runelite, osuddy and konduit). However, I don't think they take it into account when banning. The only thing I've noticed is they ban you when you have very similar interactions (doesn't matter about mouse movements).

---

## [p2] Protoprize — 2019-04-07T14:10:56Z

On 4/7/2019 at 2:47 PM, manko said:

im sorry botting smart is not part off it i made a new account did some hand leveling and some quests did 1 hour off mining then got some sleep next day account pem banned so its more than botting smart that stops you gets banned.

Then you gotta find me an explanation as to why my botting routines don't get me banned ?

I use mirror mode only btw.

---

## [p2] manko — 2019-04-07T14:19:20Z

On 4/7/2019 at 4:10 PM, Protoprize said:

Then you gotta find me an explanation as to why my botting routines don't get me banned ?

I use mirror mode only btw. 

yer iv started using MM but what happened to me still shows it more than just running a bot and no matter how long you bot even if its just logging on using the bot you can get banned so trying to say its based off time spent playing is foolish. tho it my have somethink to do with how much data they get off you botting but we all know that it a mix off stuff.

---

## [p2] Charlotte — 2019-04-07T14:39:21Z

Player reports > mouse movement

Mouse movement contributes little to none imo.
I've done tons of 100~hr progs on highly repetitive tasks such as fishing/agility/hunter etc on injection. Not a fan of mirror mode btw.
Till date, I believe player reports has the highest contributing factor to getting your account banned.

---

## [p2] CsharpBestLang — 2019-04-07T16:40:03Z

I really enjoyed this post it's shined a little more light and goes hand in hand with my own thoughts.

 

I don't think the author is trying to "bash" OSBOT API but, rather showing issues with its current state to hopefully find a safer way to bot. Without people investigating or looking to innovate, we would be falling closer and closer to this cat and mouse chase. I really do believe that jagex has an algorithm either herustic of some form, machine learning, or whatever and lessening the patterns it can pick up on and shrinking the sample size to perform those analyses will greatly increase time with your account before being banned.

 

I think the author did a great job in pointing out that mouse movements are being sent to their servers with proof via a github repo that was RE. Good find.

---

## [p2] Patrick — 2019-04-07T17:00:40Z

On 4/7/2019 at 6:40 PM, CsharpBestLang said:

I really enjoyed this post it's shined a little more light and goes hand in hand with my own thoughts.

 

I don't think the author is trying to "bash" OSBOT API but, rather showing issues with its current state to hopefully find a safer way to bot. Without people investigating or looking to innovate, we would be falling closer and closer to this cat and mouse chase. I really do believe that jagex has an algorithm either herustic of some form, machine learning, or whatever and lessening the patterns it can pick up on and shrinking the sample size to perform those analyses will greatly increase time with your account before being banned.

 

I think the author did a great job in pointing out that mouse movements are being sent to their servers with proof via a github repo that was RE. Good find.

Expand

 

The discussion about what happens to the mouse data thats being sent to the server, is something that has been going on for years. In the end, everyone is just guessing

---

## [p2] CsharpBestLang — 2019-04-07T17:20:44Z

On 4/7/2019 at 7:00 PM, Patrick said:

 

The discussion about what happens to the mouse data thats being sent to the server, is something that has been going on for years. In the end, ev﻿eryone is just guessing

Hmm alright, I guess the biggest thing here is the fact that they are storing the information into packets and sending them out. True this could be speculation, hell maybe they just wrote that code out for other reasons and it isn't even part of their automated detection algorithm. None the less it's still a scary thing to look at when botting.

---

## [p3] ThoughtVNC — 2019-04-07T17:24:30Z

Listen, even if mouse data had been tracked or not OSBot definitely has a higher ban rate than AHKs.

I've experienced a lower ban rate using MM, but that's just my experience.

Tracking mouse data in any capacity is very hard to believe, clicks per minute of a bot are very different however,

Bots will make less mistakes than a human and click at consistent RPM.

If anything, bots need to double click, spam click in some cases, become more delayed over time, use middle mouse button to change camera angle, etc etc.

For those who pose the idea that "the API needs to be reworked", I can assure you this would not be necessary.

If anything the API needs certain changes not to emulate human behaviors better but to evade ban detection.

I can assure you that some scripts have a much lower ban rate than others, scripts that are very popular often have a higher ban rate; those that are outdated have a higher ban rate.

If anyone wants to discuss this further with me, please PM and we can chat there.

thoUghTVnC

---

## [p3] ProjectPact — 2019-04-07T17:33:24Z

On 4/7/2019 at 9:14 AM, asdttt said:

You said, and I quote, "I would have to discuss with the others about changing the mouse movement". You never confirmed whether the mouse movement would be improved..? I figured you and the other devs simply came to the conclusion that the mouse movement was fine, because.. What else was I suppose to believe...?

Maybe your testing is outdated. I couldn't find a single miner bot on this entire form, paid or free, that bypassed 3 hours of mining. I had to make my own script to bypass, and only until I stopped using some of OSBot's API was I able to successfully bypass and still to this day I've yet to be banned. Even agility training is bypassing now, which is a very high banrate due to the amount of mouse movement required in most courses. It's got a lot of human-like behavior too like not being consistent, taking tiny breaks - like emulating a human moving the mouse off screen to click something in another window, ect.

And yeah, it doesn't account for the majority of their detection's, but it still can and will lead to a ban - which in my case, was the only reason I was actually being banned. 

Expand

That's awkward....

---

## [p3] Night — 2019-04-07T17:44:47Z

On 4/7/2019 at 7:33 PM, ProjectPact said:

That's awkward....

Expand

How dare you suggest it's his testing method which is flawed?

---

## [p3] Protoprize — 2019-04-07T18:27:03Z

On 4/7/2019 at 7:44 PM, Night said:

How dare you suggest it's his testing method which is flawed?

How can you suggest that someone else is suggesting that their testing method is flawed? ?

---

## [p3] asdttt — 2019-04-07T22:08:33Z

Instead of assuming everything I'm saying is false, test for yourself I suppose. 

Does OSBot produce consistent moue patterns? Yes

Can you scew these mouse patterns using OSBot API? Yes, although I'd personally recommend you just make yourself your own random mouse mover which will hide the OSBot consistent flaws and use OSBot to still click entities. It doesn't need to be advanced, hell you could even record your own small movements and apply it. Maybe there's a mouse mover utility within OSBot that doesn't produce these same flaws that I missed? Moving the mouse outside the screen DOES NOT produce patterns. The best way to see whether you're being detected by mouse is to check yourself whether patterns are being consistently repeated.

Does Jagex detect stealth injection? No (I scavenged through the entire RS sourcecode)

Does Jagex have code that can detect OSBot? Yes, although the server would need to manually send a check-class packet on one of OSBot's classes loaded in the VM instance - which they don't appear to ever do. But they have the ability to, so keep that in mind.  Mirror mode, assuming it doesn't load any classes inside the RS VM would not be detectable by these means. Although I highly doubt they detect bots  through this, and if they do, it'd probably be incredibly rare in order to preserve this check otherwise it'd be easy as fuck to bypass it. https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L4315

Are mouse clicks detected from OSBot? No, OSBot does a fantastic job with pretty much all button related actions. Here's proof they use mouse time/location and ship it to the server: https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3371

Is moving the screen detected from OSBot? No, OSBot once against does a fantastic job emulating a normal user by moving the screen with keys. 

 

Just botted 15 hours of mining on a fresh level 3 straight through the night. 0 ban. Don't give up so easily and accept your fate of a daily, or weekly ban. It's not a lost fight, it's just the people bypassing want to continue to bypass so they tend to hide their bypass methods from the public lol. There's no single method in bypassing either, so don't assume messing with mouse movement will suddenly make you bypass. I personally recommend you record yourself playing for 20 minutes, then apply your own timings, AFK-ness, reaction time, and mouse pattern (such as moving the mouse outside the window, or randomly moving it around) to your script. 

 

And keep in mind, none of this applies to manual bans. It's very possible a moderator will still find your botting ass and manual ban. How exactly they differ a normal player from a botting player is unknown. I doubt they just walk up to you and ask if your a bot, if no response, ban. That would be very unprofessional and would lead to the banning of foreign people who don't understand English lmao

 

*Disclaimer: These are my own tested findings. Whether they're accurate or not is another story.

 Edited April 8, 2019 by asdttt

---

## [p3] asdttt — 2019-04-07T22:15:00Z

On 4/7/2019 at 11:04 PM, Malcolm said:

Patrick is right.

I probably didn't mention this here earlier but I have mentioned it in other posts.

Nobody knows how Jagex detects bots except for the employees at Jagex.

Everybody could debate over it but its all speculation of what actually factors into Jagex's bot detection system.

What I am certain about is that Jagex would never make their detection methods accessible to the public, especially their code for it and that you'll never know how they detect bots unless you either:

Work at Jagex.
A Jagex employee tells you.

Both of these are very unlikely scenarios so we are left here just guessing.

Well let's start with, we know what jagex takes from the client, and we know what Jagex sends to the client. We know that mouse movement is sent, so why not fix this flawed mouse movement. We know that clicks are sent, so why not have human clicks (Which we do, thx OSBot). Yeah sure there's guessing into what they do with the data collected, but there isn't any guessing as to what data  they're collecting. And IMO that's more then enough.

---

## [p3] asdttt — 2019-04-07T22:18:20Z

On 4/7/2019 at 7:44 PM, Night said:

How dare you suggest it's his testing method which is flawed?

??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

I provided the code.. It's super basic math grabbing the differences between mouse movement on a 50MS tickrate. Are you saying java's sqrt function is flawed? Yikes...

---

## [p3] Juggles — 2019-04-07T22:47:35Z

So are you suggesting to add random mouse movements before/after interactions?

mouse.moveRandom();

npc.intereact();

mouse.moveRandomFromCurrentMousePosition();

mouse.offScreen();

Is this what you are proposing?

---

## [p3] asdttt — 2019-04-07T23:04:02Z

On 4/8/2019 at 12:47 AM, Juggles said:

So are you suggesting to add random mouse movements before/after interactions?

mouse.moveRandom();

npc.intereact();

mouse.moveRandomFromCurrentMousePosition();

mouse.offScreen();

Is this what you are proposing? 

My point is, for instance, when you click a moss giant legit. What do you do? I personally move my mouse off screen and AFk kill the giant. Introduce human behavior into your scripts without accidentally creating a pattern (In terms of hard data)  is what I'm saying. 

Not going to go into anymore detail because I've said more then enough. Mouse movement's detected, now it's everyone's jobs to adapt their scripts if they're being banned, to scew mouse data heuristics. Not my job to tell you how to do it

 Edited April 8, 2019 by asdttt

---

## [p3] Koschei — 2019-04-07T23:08:21Z

Some people refer to me as the Chad of Runescape botting. What do I do to get so swole you may ask, it's simple. I hover over my woodcutting skill no matter what I do, all these people are trying complex calculations to figure out the secret, but they've all left that one method, which has flawed all their research.

---

## [p3] asdttt — 2019-04-07T23:12:21Z

On 4/8/2019 at 12:45 AM, Malcolm said:

Ok I'll bite and go off the basis that mouse movement is sent and analyzed.

Now you are left guessing how the mouse is analyzed, which aspect of the mouse is analyzed and you're playing cat and mouse on how to "fix it".

Dude you literally called out a developer of osbot suggesting that his testing is outdated and flawed.

You've also said that you have produced evidence of a fix where really you haven't because nobody knows what the problem really is except for the employees at Jagex.

So unless you're actually Mod Weath you really don't know how to solve any sort of detection issues, you like us, are guessing.

Why do you think they collect mouse data...? To waste their bandwidth for fun? This is really annoying me. Please stop being ignorant lol

Which part of the mouse is analyzed? Huh? Did you not even read my post? Wtf? 

Uh yeah do you know when the mouse movement was first introduced...? It's absolutely outdated and his tests were from like 2 years ago wtf? 

Evidence? I'm not banned after changing my mouse movement. There you go..

Here let me just go to jagex's datacenter and yoink one of their servers so we can view their exact code /s.

Enough wasting my time. You're in denial, not my problem. OSBot's detected. Get over it. They can fix it and even the current developer admitted that the mouse is flawed.

---

## [p3] Juggles — 2019-04-07T23:16:02Z

On 4/8/2019 at 1:04 AM, asdttt said:

My point is, for instance, when you click a moss giant legit. What do you do? I personally move my mouse off screen and AFk kill the giant. Introduce human behavior into your scripts without accidentally creating a pattern (In terms of hard data)  is what I'm saying. 

Not going to go into anymore detail because I've said more then enough. Mouse movement's detected, now it's everyone's jobs to adapt their scripts if they're being banned, to scew mouse data heuristics. Not my job to tell you how to do it

There's already a mouse.moveOutsideScreen(); method on OSBot. I use it in all my scripts to act as if I am AFK watching Netflix

---

## [p3] asdttt — 2019-04-07T23:21:13Z

On 4/8/2019 at 1:16 AM, Juggles said:

There's already a mouse.moveOutsideScreen(); method on OSBot. I use it in all my scripts to act as if I am AFK watching Netflix

Yup and like I said, it doesn't contain any flaws from my testing. That's why I recommended it from any other "randomization" from inside the Mouse API. Still though, they compare large/small/medium movements against different checks, so you still should probably include some small/medium randomization that doesn't include OSBot's API (Assuming you don't have an API method that doesn't produce the same "endpoint" flaw I've discussed).

Even keeping the mouse perfectly still is better then moving it randomly around with OSBot's mouse API. 

 
On 4/8/2019 at 1:20 AM, Malcolm said:

@asdttt

I don't think you get it.

You're analyzing the osbot mouse and telling the scripters that they make a shitty product and they should do all these things which I know you haven't mentioned it but I will for you, to make the bot undetectable.

I'm not responding to you. You wont even bother reading anything I've said or testing yourself. Mouse is flawed, get over it

---

## [p3] Molly — 2019-04-07T23:28:19Z

I think the important thing to take away here is that they do at least send the mouse data to their servers, it doesn't look very human, and if they wanted to they could use this as one of many metrics to detect bots. 

That alone should be enough to encourage a change, whether it be in the way OSBot itself handles mouse movements or by us scripters to handle movement ourselves. 

 Edited April 8, 2019 by Molly

---

## [p3] asdttt — 2019-04-07T23:29:37Z

On 4/8/2019 at 1:27 AM, Malcolm said:

Are you dense? I haven't denied this at any point in time LOL

Then why the hell are you arguing with me LMAO...

---

## [p4] Juggles — 2019-04-07T23:32:41Z

On 4/8/2019 at 1:21 AM, asdttt said:

Yup and like I said, it doesn't contain any flaws from my testing. That's why I recommended it from any other "randomization" from inside the Mouse API. Still though, they compare large/small/medium movements against different checks, so you still should probably include some small/medium randomization that doesn't include OSBot's API (Assuming you don't have an API method that doesn't produce the same "endpoint" flaw I've discussed).

Even keeping the mouse perfectly still is better then moving it randomly around with OSBot's mouse API. 

I'm not responding to you. You wont even bother reading anything I've said or testing yourself. Mouse is flawed, get over it 

You've posted a problem but I don't see any solution. I want to test your proposed solution myself and see if what you're saying is true. 

But the only way to truly test your hypothesis is to run n=1000+

500 with default and 500 with your proposed movements. Even 1000 bots might not be large enough of a sample size.

---

## [p4] asdttt — 2019-04-07T23:38:52Z

On 4/8/2019 at 1:32 AM, Juggles said:

You've posted a problem but I don't see any solution. I want to test your proposed solution myself and see if what you're saying is true. 

But the only way to truly test your hypothesis is to run n=1000+

500 with default and 500 with your proposed movements. Even 1000 bots might not be large enough of a sample size. 

Solution is to implement better mouse movement that doesn't produce the same flaws (Although using OSBot's mouse + your own mover is fine too). I can't hand you all my own mouse mover or then I'd have to be worried about Jagex finding flaws in my own mouse mover and banning my ass. 

Just take what I've said, and fix it yourself. You're all knowledgeable with Java, and all have scripting experience. And maybe hopefully they'll implement better/different mouse movement on OSBot and then we'll only have to worry about the other ban  checks.

---

## [p4] asdttt — 2019-04-07T23:42:50Z

On 4/8/2019 at 1:36 AM, Malcolm said:

The only thing I am saying is that you like us are guessing.

You don't know what is analyzed about the mouse although I agree it is sent and it is analyzed.

Let me put this plain and simple for you.

Is the mouse detected? Probably. (I wont say yes and I won't say no because I don't actually know for sure but it is more than likely that it is detected and analyzed)

Do you know how to fix it? No,

Do I know how to fix it? No.

Do the developers know how to fix it? No.

Do the employees at Jagex know how to fix the bot detection issues? Yes, because they are the only ones who know how it works and they are the only ones who know a workaround it.

Now, unless Jagex's bot detection methods are leaked nobody knows how to fix anything.

This is the only point I was trying to make to you. I am not denying any detection.

Expand

 If you would of read my post, you'd be fully aware that OSBot's mouse is flawed. Stop arguing and wasting my time. I've literally told you the same thing with different wording many times. Not only that, you've done NOTHING to test what I'm sampling, yet you are very quick to deny everything. Evidence for denying? None. 

Can YOU, or anyone else detect OSBot based on mouse delta? YES. Look at my sample and tell me that looks human. Does Jagex detect this huge flaw? In my study, YES. Could they detect this huge flaw? YES, VERY VERY EASILY. Are you wasting my time? YESS!

 

Edit:

Do you know how to fix it? Yes

Do I know how to fix it? I'd hope so

Do the developers know how to fix it? Absolutely.. It's not that difficult. Patrick's a smart guy

 Edited April 8, 2019 by asdttt

---

## [p4] Molly — 2019-04-07T23:43:49Z

On 4/8/2019 at 1:36 AM, Malcolm said:

The only thing I am saying is that you like us are guessing.

You don't know what is analyzed about the mouse although I agree it is sent and it is analyzed.

Let me put this plain and simple for you.

Is the mouse detected? Probably. (I wont say yes and I won't say no because I don't actually know for sure but it is more than likely that it is detected and analyzed)

Do you know how to fix it? No,

Do I know how to fix it? No.

Do the developers know how to fix it? No.

Do the employees at Jagex know how to fix the bot detection issues? Yes, because they are the only ones who know how it works and they are the only ones who know a workaround it.

Now, unless Jagex's bot detection methods are leaked nobody knows how to fix anything.

This is the only point I was trying to make to you. I am not denying any detection.

Expand

I'm not sure it's fair to say nobody knows how to fix this. A straightforward solution would be to record a couple thousand mouse paths of varying distances and base your script's mouse movements off these. Example: I need to move my mouse 70 pixels, I grab from my thousands of human mouse paths some that are around 70 pixels, say between 40-100. I grab one of those paths, stretch it or shrink it, add some noise to it and use that path to move the mouse.

The downside to this is it requires a fair amount of work recording that many mouse paths and realistically is easier to do and probably better for not being "detected" if done by each scripter individually for their own scripts.

 Edited April 8, 2019 by Molly

---

## [p4] asdttt — 2019-04-07T23:50:02Z

On 4/8/2019 at 1:43 AM, Molly said:

I'm not sure it's fair to say nobody knows how to fix this. A straightforward solution would be to record a couple thousand mouse paths of varying distances and base your script's mouse movements off these. Example: I need to move my mouse 70 pixels, I grab from my thousands of human mouse paths some that are around 70 pixels, say between 40-100. I grab one of those paths, add some noise to it and use that path to move the mouse.

The downside to this is it requires a fair amount of work recording that many mouse paths and realistically is easier to do and probably better for not being "detected" if done by each scripter individually for their own scripts.

It's not easy to code a mouse mover, but you'd only need to account for a few factors (If you want me to show proof, I'll DM you the code). 

Factors are: Reaction time, reaction time variation, mouse speed, mouse speed variation, mouse step variation, deviation (Very important, mind you, the human wrist cannot easily move the mouse without deviation), noise, and OVER-move (When you move  your mouse, but accidentally go over a button and have to return to it). Edit2: (All of these should also be editable by scripters. A 12 year old would have different reaction time and mouse speed compared to a pro CS:GO player for instance)

edit: Not denying your solution, that'd also work. Just adding on that you can make a realistic mouse mover with code

 Edited April 8, 2019 by asdttt

---

## [p4] asdttt — 2019-04-07T23:54:07Z

On 4/8/2019 at 1:45 AM, Malcolm said:

What have I denied? Please elaborate?

^ I certainly haven't denied this.

Idk everything you're saying is dumb so I just assume you are denying everything lmao. You literally just said the OSBot developers can't fix the flaw. Like whaaaat..?

---

## [p4] Molly — 2019-04-07T23:59:12Z

On 4/8/2019 at 1:50 AM, asdttt said:

It's not easy to code a mouse mover, but you'd only need to account for a few factors (If you want me to show proof, I'll DM you the code). 

Factors are: Reaction time, reaction time variation, mouse speed, mouse speed variation, mouse step variation, deviation (Very important, mind you, the human wrist cannot easily move the mouse without deviation), noise, and OVER-move (When you move  your mouse, but accidentally go over a button and have to return to it). Edit2: (All of these should also be editable by scripters. A 12 year old would have different reaction time and mouse speed compared to a pro CS:GO player for instance)

edit: Not denying your solution, that'd also work. Just adding on that you can make a realistic mouse mover with code

Oh yeah you can for sure make it better than the barebones example I gave, was just trying to illustrate the point that this isn’t something without a solution that’s impossible to figure out.

---

## [p4] Naked — 2019-04-08T00:16:49Z

On 4/8/2019 at 2:07 AM, Malcolm said:

No, I have not denied anything.

I am telling you that the only thing we can do is guess what the issues are.

Can the flaws be looked at and the mouse movements be changed? Yes.

Will these changes solve anything? I understand that you are claiming that it does and dude I am all for this. If it is possible to make an "undetectable bot" it would be incredible really, but, we don't know if the changes will solve anything. We are guessing and it is trial and error.

Does it hurt to try? Absolutely not.

I am not trying to say you haven't found anything. Just like you I am pointing out flaws in your analysis. The biggest flaw being your sample size.

Like I suggested you can be far beyond 2-3 standard deviations and your sample potentially doesn't accurately represent the mean. I am not saying it doesn't.

I certainly don't disagree with anything that you're doing here.

What I do disagree with is

Calling out developers in a negative way
Calling out scripters in a negative way

 

What I would like to see is you run hundreds or thousands of bots and then statistically analyze it and return with your findings because it is very possible that once you run it on a larger scale that you end up with the same ban rate as the regular mouse.

Expand

Hard to test at scale when you're gray ?

 

Correlation doesn't imply causation blah blah blah small scale tests mean nothing.

---

## [p4] asdttt — 2019-04-08T00:20:32Z

On 4/8/2019 at 2:07 AM, Malcolm said:

No, I have not denied anything.

I am telling you that the only thing we can do is guess what the issues are.

Can the flaws be looked at and the mouse movements be changed? Yes.

Will these changes solve anything? I understand that you are claiming that it does and dude I am all for this. If it is possible to make an "undetectable bot" it would be incredible really, but, we don't know if the changes will solve anything. We are guessing and it is trial and error.

Does it hurt to try? Absolutely not.

I am not trying to say you haven't found anything. Just like you I am pointing out flaws in your analysis. The biggest flaw being your sample size.

Like I suggested you can be far beyond 2-3 standard deviations and your sample potentially doesn't accurately represent the mean. I am not saying it doesn't.

I certainly don't disagree with anything that you're doing here.

What I do disagree with is

Calling out developers in a negative way
Calling out scripters in a negative way

 

What I would like to see is you run hundreds or thousands of bots and then statistically analyze it and return with your findings because it is very possible that once you run it on a larger scale that you end up with the same ban rate as the regular mouse.

 

 

 

Expand

How many bots do you think I've tested with...? 5...? No.. Well over a hundred. How many miner bots am I using right now? 3, simply because it uses lots of ram/cpu. Although when I'm not using my PC, about 10-13 max

Let's move onto the actual subject, and then hopefully this argument will be over. I sampled the same data as Jagex, on the same interval. I found that not only is OSBot's mouse movement very robotic, but it also has constant flaws. I posted the sample comparing it to a humans mouse movement so everyone could see the major flaw. 

Now onto my "sample size" of banrates after changing my mouse movement. Been running miner bots, each for 6  hours a day, each on it's own paid proxy IP, each at the same exact mine. How many bans have I gotten? 0. How much profit? Surprisingly not that much because iron is fucking worthless. 

Minerbot results (Not including my other bots scripts): 

Before implementing new mouse movement: 56/56 bans - Each bot lasting exactly 1 day

After implementing new mouse movement: 0/12 bans (Only have 12 accounts because there's no need to make more)

 

Now banrate samples ARE NOT to be taken as evidence, but rather reference. These can very easily be inaccurate for all I know. 

What you CAN however take as factual evidence is the samples of OSBot's mouse movements. That's consistent, and you can see for yourself the output. 

 Edited April 8, 2019 by asdttt

---

## [p4] Impensus — 2019-04-08T00:24:55Z

On 4/8/2019 at 2:20 AM, asdttt said:

How many bots do you think I've tested with...? 5...? No.. Well over a hundred. How many miner bots am I using right now? 3, simply because it uses lots of ram/cpu. Although when I'm not using my PC, about 10-13 max

Let's move onto the actual subject, and then hopefully this argument will be over. I sampled the same data as Jagex, on the same interval. I found that not only is OSBot's mouse movement very robotic, but it also has constant flaws. I posted the sample comparing it to a humans mouse movement so everyone could see the major flaw. 

Now onto my "sample size" of banrates after changing my mouse movement. Been running miner bots, each for 6  hours a day, each on it's own paid proxy IP, each at the same exact mine. How many bans have I gotten? 0. How much profit? Surprisingly not that much because iron is fucking worthless. 

Minerbot results (Not including my other bots scripts): 

Before implementing new mouse movement: 56/56 bans - Each bot lasting exactly 1 day

After implementing new mouse movement: 0/12 bans (Only have 12 accounts because there's no need to make more)

 

Now banrate samples ARE NOT to be taken as evidence, but rather reference. These can very easily be inaccurate for all I know. 

What you CAN however take as factual evidence is the samples of OSBot's mouse movements. That's consistent, and you can see for yourself the output. 

Expand

How are you using 3 bots at once when greys are limited to 2 bots per client?

---

## [p4] asdttt — 2019-04-08T00:27:26Z

On 4/8/2019 at 2:24 AM, Impensus said:

How are you using 3 bots at once when greys are limited to 2 bots per client?

Can't elaborate without getting banned off here

---

## [p4] asdttt — 2019-04-08T00:29:20Z

On 4/8/2019 at 2:28 AM, Malcolm said:

So you have multiple accounts avoiding VIP?

For all you know I have an alt with a paid subscription. Don't worry about it. It's for the best

---

## [p4] Naked — 2019-04-08T00:30:39Z

On 4/8/2019 at 2:29 AM, asdttt said:

For all you know I have an alt with a paid subscription. Don't worry about it. It's for the best

Alt accounts are against the rules too 

 Edited April 8, 2019 by Naked

---

## [p4] asdttt — 2019-04-08T00:31:15Z

On 4/8/2019 at 2:30 AM, Malcolm said:

Multiple accounts are against the rules.

It's my brother's account obviously MonkaS

---

## [p4] Naked — 2019-04-08T00:32:47Z

On 4/8/2019 at 2:31 AM, asdttt said:

It's my brother's account obviously MonkaS

So you're sharing accounts? Also against the rules.

---

## [p5] asdttt — 2019-04-08T00:34:21Z

On 4/8/2019 at 2:32 AM, Naked said:

So you're sharing accounts? Also against the rules.

Idk feel free to ban me I guess..? Wont help the client in anyway though

---

## [p5] asdttt — 2019-04-08T01:00:56Z

On 4/8/2019 at 2:41 AM, Malcolm said:

I don't think you'd get banned, just the alt accounts that you have would be. You're only allowed to use one account. (Unless your other account was banned for whatever reason then you'd be banned but I don't think you're the scammer type based on the conversation here)

We are however off-topic and I think what you have is very interesting.

This might seem like a troll and it's not. This is an actual question.

Can you tell us exactly what Jagex is tracking when it comes to the mouse? I mean every aspect of the mouse, not just that they track the mouse because this has been established.

And if we somehow got access to Jagex detection methods, would they suggest exactly the same thing you would tell us?

 

 

Expand

Read the initial post. There is where I post where they feed the an array your mouse positions on a 50MS tick cycle, then I also post were they ship it off. 

Few things the code tells me as to what they're tracking:

||) Equal, or zero movements are tracked by ticks. If you don't move your mouse for 30 ticks, they'll know. 

1) Movement of the mouse is tracked, smalls/medium movements exactly by this (Reconstructed):

int yDiff = (recordedY - previouslyRecordedYMove); 

int xDiff = (recordedX - previouslyRecordedXMove);

handler.packetBuffer.putShort(yDiff + (idleIndexesPassed<< 12) + (xDiff << 6));

idleIndexesPassed = 0;

 

movementIndex = the indexes skipped before finding a mouse move in the X/Y mouse recorder. Used to track time between mouse movmenets. 

 

2) Larger movements that are made in less then 8 ticks of "idle" mouse:

int var10 = (recordedY * 765 + recordedX);

handler.packetBuffer.put24bitInt((idleIndexesPassed << 19) + var10 + 8388608);

idleIndexesPassed = 0;

 

3) Large movements that are made 8+ ticks from being "idle"

int var10 = (recordedY * 765 + recordedX);

var14.packetBuffer.putInt((field608 << 19) + var10 + -1073741824);
idleIndexesPassed  = 0;

 

The majority of OSBot's movements would fall under #1's logging. The others are just for larger mouse movements (in terms of last X/Y -> new X/Y)

What does that mean? Well for starters, they can easily pickup on those last 3-4 samples of OSBot's flawed mouse movement as it's sent to the server. 

 

Edit: Why do they record mouse movements like:  (recordedY * 765 + recordedX);?! Well, they do that because they're lazy and combine the integers as so. You see, they cap the X axis at 764, therefor it can never be over 765. So getting the recordedY from the recordedX in that single integer is basic math. It's just like that for performance

So they could fetch the X/Y of a large movement from that recorded integer like:

int combined = (100 * 765 + 50); #Equals 76550 

int y = (76550 / 765);
int x = (76550 % 765);

 Edited April 8, 2019 by asdttt

---

## [p5] asdttt — 2019-04-08T01:15:21Z

On 4/8/2019 at 3:07 AM, Malcolm said:

@asdttt I'll reiterate

How do you know this is what Jagex is using for bot detection?
Do you think Jagex would have their detection methods accessible outside of the company?
Do you know every aspect of Jagex's bot detection methods when it comes to the mouse?

 

1) I mean they're logging the differences between X/Y for small/medium movements, and the position for larger movements. They're also logging the time between each movement. Therefor, they're most certainly viewing/checking individual mouse movements, just as I did in my sample. They have all the data they need to figure out OSBot's flaws


2) ????. Of course they wouldn't show their "top secret methods" (Which btw, are a lot more common then you think lol). But as you can cleaaaarly see, they're logging mouse data, and the perfect amount to figure out the flaw for themselves. 

 

3) Based on previous experience with hacking/anti-cheats, I can relate to some of the detection methods they're most likely using. Flaws being the easiest, while patterns not being far behind. Do I personally know what they do with the data? No? But can I speculate and use the same data they're collecting to see if I too can detect it? Absolutely yes!

 

Jagex didn't invent Heuristic/anti-cheat. They've merely implemented it into their own system and tuned it to fit. We know what data we're sending them, and we can manipulate that data however we want. 

So lets start with the flaws, then move onto the patterns. Current most obvious flaw is the mouse movement - which as I've showed you.. Jagex has this same data being sent to them (I literally rebuilt the obfuscated code for u wtf). 

 

Edit: I'll give a prime example of a flaw in one of my aimbots on battlefield. I was making my aim based on YAW/Pitch of my character/camera, rather then mouse delta + sensitivity factors. Why is this a flaw? Well, you see, the camera is moved based on mouse delta, therefor if my yaw/pitch movement wasn't align with what a mouse would of made, it's easily detected

 Edited April 8, 2019 by asdttt

---

## [p5] asdttt — 2019-04-08T01:49:41Z

On 4/8/2019 at 3:32 AM, Malcolm said:

This is the point I was trying to get at is that we are all speculating what Jagex does with the data, what parts of the data is really being analyzed and finally, what really leads to a conviction.

Is the mouse flawed? Yes

Are you onto something? Probably.

Mind you the client itself needs to know where the mouse is and this could just be for game-play. I am not going to deny the mouse is tracked. If they need the mouse for game-play tracking the mouse for bot detection is not far off.

But if we have established that we don't know what they do with the data how do we know that their methods of detection are common? We don't and again this is speculation.

It is all speculation and trial and error. Some things will work and some things will not work.

The mouse is also just a piece of the puzzle.

Again your findings are very interesting but is it solved? I don't believe you have solved the problems. I believe you are on the right track though.

 

Expand

Well here's what throws our entire argument under the bus. You don't believe anything I say. 

We'll never know what they do with the data. No fucking anti-cheat to ever exist blatantly tells hackers how they're being detected so please stfu with that. Everything is not based on trial an error unless you decide to take that route. For me, everything is based on being able to analyze the very data they are analyzing and seeing if I can pickup on it. If I can, change it. If I can't, then I'll move on to trail and error. 

The only reason I'm suddenly bypassing on all my scripts is because of using new mouse movement. Whether you want to believe that is up to you. But imo, that's a pretty fucking solid link lmao. 

Enough of this argument. I've shown more then enough evidence. You can keep saying "Oh yeah!! Well maybe they collect thousands upon thousands of mouse samples so they can tie it in with the game engine! Yup!" Even though - BTW, they only started doing this AFTER they implemented the new heuristic checks... HMM

 

What's absolutely known: OSBot doesn't have human mouse movement

 

Leave it at that and please don't bother me with another one of your "OH YEAH BUT HOW DO YOU KNOOOOW THEY ARE USING THIS DATA TO DETECT MEE!!". Data is present on their servers. Same data is detectable by myself and anyone else who reads my samples. Therefor, needs to be changed  

Edit: I didn't even fully read you because I was pissed, but:

"The mouse is also just a piece of the puzzle."

Yeah no shit. Once this is fixed, I'll move onto other OSBot API related aspects that are easily detected (If there is any). Until then, fix the mouse movement

 Edited April 8, 2019 by asdttt

---

## [p5] asdttt — 2019-04-08T02:13:23Z

On 4/8/2019 at 4:08 AM, Malcolm said:

 

How many times do I need to tell you that I think you're on the right track?

 

Just stop it at this: OSBot needs new or altered mouse movement. The current CAN BE easily detected based solely off the data collected by Jagex. IMO, and in my own tests, it is. Whatever that means to you is up to you. 

 

Edit: And I'm not going to waste my whole week building a bigger sample size for you guys. I like this community, but not THAT much lol. Either take what I say into consideration, or don't. Completely up to you. But I wont ever deny what I've said here. 

 Edited April 8, 2019 by asdttt

---

## [p5] asdttt — 2019-04-08T02:34:59Z

On 4/8/2019 at 4:30 AM, Malcolm said:

If you do want to create a larger sample size (yes I can see here that you say that you don't want to do that) I have just over 2k fresh accounts that have not completed Tutorial Island.

If you are interested in creating a larger sample size I would provide you these accounts (I will give you the details about creation if you want to build a larger sample).

Feel free to PM me on OSBOT if you're ever interested or you want to test anything else and need accounts.

 

Nah I'm good. Why waste my time on people who don't believe me despite the evidence I've brought forward. Hell I bet even after those samples, those same people will still express concerns about my samples lol. I'm sure some people at least took something from this topic which is all I can hope for.

---

## [p5] THS — 2019-04-08T07:11:08Z

Just wanna stick my nose in and point out that this is on a public forum 

 Edited April 8, 2019 by THS

---

## [p5] asdttt — 2019-04-09T03:05:05Z

I added some more explanation as to how certain mouse movements are logged for anyone who was confused by the obfuscation or integer compression.

---

## [p5] WastedWrath — 2019-04-10T10:11:14Z

These are the kind of discussions needed (without the insults of course) on this forum.

I find it incredulous that there are lots of people here that will happily try and shrug this discussion off with "no one knows why you get banned".

Perhaps stats based evidence is the best way to go rather than "no one knows, dont bot what you dont want to lose"

---

## [p5] asdttt — 2019-04-15T11:52:38Z

On 4/15/2019 at 11:45 AM, Alek said:

@asdttt @Malcolm @dreameo @Patrick

Antiban doesn't matter - plain and simple.

If you do any research into official claims made by Jagex, you can see why. They claim that both autoclickers and simulated mouse keys are detectable, and yes people do get banned for using them. For an autoclicker, the mouse doesn't move at all (don't get me started on pseudo number random generators for sleep time).

Gary's Hood and AutoHotKey are detected, both which use SendInput - which is Windows API. My thoughts are that they are just checking the stacktrace of mouse events and determining their source. 


Additionally a while back they determined that HD clients are indistinguishable from botting clients, which also makes me believe they are looking at the garbage collector.

But of course, go play around with antiban like everyone else has for the last 15 years - I'm really pessimistic in your results (nothing personal, but it's really a naiive approach).

Expand

Well for starters, let's accept the fact that Jagex sends mouse movements to the server for analyzing, AND I myself was able to very very easily pickup on OSBot's using basic math using the exact same data they're sent. There's serious flaws in the movement, mostly the last bit where it ends. Please review my research so you too will actually see these flaws. I have no idea how you find this to be naive, especially since a fairly large amount of anti-cheats rely on mouse DPI movements to detect certain hacks - even aimbot. OSBot's mouse movement is flawed, as simple as that. If you dispute that fact, please give me insight because I'd love to see you defend that obvious pattern... FYI, many anti-hacks actually use 50MS tick sampling. Hell, minecraft's servers RUN on 20 TPS. It's a great number for anything but visual rendering. To 

Next, I've searched through the runescape source numerous times and yes. To say we're not ever able to bypass is a bit absurd considering WE are feeding them the data. You need to remember that even though Jagex made the client, they're still on a virtual machine. Java is VERY VERY manipulable on purpose due to how high level it is, and the nature of how it's deployed on many platforms - which I'm sure you already know. Autoclickers are easily detected because, as you said, they do not execute the same click as a mouse would. Although an internal autoclicker, as far as they know, does not produce the same flaws and you're then in control of nearly every factor including press/hold timings (Assuming you don't create flaws...). They also do not do any stracktrace checks from what I've seen, although tomorrow I'll be sure to search for that. If you'd like me to provide hard evidence on click sampling, I'll do so tomorrow. It's hard to elaborate without providing at least something to back me up. 

So for example: https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3371

That's where they send the timings of the mouse, and here's where they track the mouse input/timings (Using normal Java events)...

https://github.com/zeruth/runescape-client/blob/master/src/MouseInput.java

Also if they did detect all autoclickers, that wouldn't explain the HUGE amount of people using them on their mains and never getting banned. 

 

As for the garbage collector... Your actually absolutely correct on this one. Jagex at ANY time can request a sample of your garbage collector. IMO a garbage collector simply can't be enough to distinguish a bot client from an official client, but they could probably detect a difference between a non-official client and official. Although I could be wrong as I myself haven't personally tested the garbage collector timings, oldGen size, newGen size, or frequencies between collections. This would be banning solely on assumption though which I doubt Jagex would do.

Here's  the nearly-de-obfuscated code for that: https://pastebin.com/FzdBmKPL

It's just measuring the actual collection events rather then memory consumption. Still though, this is a very unstable measurement and I doubt they'd ban us soly based on this.

 

However.. There is one other thing they could possibly use to directly detect a bot, although I still kinda doubt they'd go to this extent. They have a class checker which they, like the GC info, can request at anytime. 

https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L4315

Weird right? I'm sure they use it for debugging purposes and possibly client compatibility/version checking, but you can't deny they couldn't use that to snoop around the JVM and find some bot classes. Especially considering how specific java packaging is. 

 

And this isn't anti-ban. The whole "anti-ban" thing makes 0 sense. There's no such thing as some magical thing that makes you unbannable. It's a combination of your bot's ABILITY that lead to a ban, not checking skills, not taking breaks, and not talking in chat. For instance, mouse movement, button hold times, mouse click-rate (Yes IMO they take account for this), mouse hold-time, and so on.

There's no SINGLE check on their anti-hack, so implementing new mouse movement wont suddenly make OSBot 100% undetectable. Although, it's a major step in the right direction. Not sure if you're aware of this, but there is a LAAARGE amount of people who bypass.. Not going to name any groups because of how censoring you guys are on here, but Ik that you know they exist so please stop pretending bypassing is impossible. Not to mention, I've still yet to be banned..?

If you'd like to discuss this in private, I'd love to show you a much better way of moving the mouse without generating patterns. 

 

-- Oh and just to close this, who really cares what Jagex says..? You really think they'd give critical detection information out to the public? I doubt they'd even tell their own moderators what they use to detect the majority of bot clients. Personally, I think they purposely spread false information just to fool botters.. I mean come on, this company couldn't even do dead man mode correctly without killing everyone. 

 

Edit: Eh I made something quick with JavaFX to further display what I meant by autoclicker flaws (Press->Release):

 My normal mouse:
 ->> Mouse press timings (MS): 78
 ->> Mouse press timings (MS): 63
 ->> Mouse press timings (MS): 59
 ->> Mouse press timings (MS): 70
 ->> Mouse press timings (MS): 75
 ->> Mouse press timings (MS): 87

 Autoclicker:
 ->> Mouse press timings (MS): 0
 ->> Mouse press timings (MS): 0
 ->> Mouse press timings (MS): 0
 ->> Mouse press timings (MS): 0
 ->> Mouse press timings (MS): 0

 

See? When you use that windows event, you have a delay of 0. This is also true if you make an internal autoclicker, but don't delay your events. Jagex is sent this data which they collect using the JVM's event's. 

 

Edit2: Also please at least give me some level of respect. I'm not some random moron shouting at potential super cool anti-ban features. I'm a fellow programmer trying to help this community by finding flaws in OSBot. Many reject me simply based on my account age, but I'm not full of shit. At least read what I have to say and test it for yourself before tossing it out the window like it's meaningless. 

 Edited April 15, 2019 by asdttt

---

## [p5] asdttt — 2019-04-16T09:28:34Z

.

 Edited April 16, 2019 by asdttt

---

## [p5] asdttt — 2019-04-16T10:31:51Z

Here's the auto-clicker I made in C++. Put simply, you need to input your minimum click delay (In milliseconds), and your maximum click delay (In milliseconds). The mouse will then emulate a normal person clicking their mouse at the rates you provided and at the current position of your mouse. Keep in mind that these rates will NOT be exact, and simply kept as some sort of target goal. It also wont click at absurd speeds like 20 clicks per second or so on.  

<LINK REMOVED BY STAFF DO NOT RE-UPLOAD>

This auto-clicker, unlike most auto clickers, does not produce flaws that the JVM could pickup on (As far as I know that is...). If Jagex somehow manages to distinguish a hardware click from a software click through some crazy magic then this will obviously get you banned. I doubt they can detect it, but I still wouldn't use it for large time periods. 

 

 

FYI: I tested Gary's Hood autoclicker, and unless I'm using an outdated version or it interacts differently with RS's window, it produces the same flaws I described above. Not recommended. To those who have bypassed using it, lucky you. IMO Jagex purposely lets people bypass at times just to further you from figuring out why the hell you're being detected lmao. Jerks

 

Timings:

 ->> Mouse press timings (MS): 51
 ->> Mouse press timings (MS): 113
 ->> Mouse press timings (MS): 147
 ->> Mouse press timings (MS): 75
 ->> Mouse press timings (MS): 72
 ->> Mouse press timings (MS): 56
 ->> Mouse press timings (MS): 57

---

## [p5] godspower33 — 2019-04-17T02:34:35Z

I'm pretty sure Jagex doesn't use mouse data to determine bans, especially since mobile release.

---

## [p5] asdttt — 2019-04-17T04:03:01Z

On 4/17/2019 at 6:00 AM, Alek said:

I disassembled your autoclicker - it is exactly the same as 99.9% of other autoclickers. The JVM can 100% pick this up, you're using a ring3 usermode Windows API - aka SendInput. You talked about mouse detection and aimbot anticheat, but you're using SendInput...

Just use AHK, you will get banned at the same rate.

Explain how you'd detect that on the JVM level.. My own mouse assigns macros using sendInput... Atm on my mouse, I use one of my bumpers for mouse clicks because the left click is broken from being dropped. 

Also the input event delays are logged as clear as day in runescapes code... 

 Edited April 17, 2019 by asdttt

---

## [p5] asdttt — 2019-04-17T04:10:25Z

On 4/17/2019 at 6:07 AM, Alek said:

Because you're using SendInput... all Windows API functions can be hooked and detected. Look into JNI/JNA (Java). Please don't say something is undetected/hardware call when you're using a usermode public Windows API function call.

My mouse's driver uses SendInput.. (Assigned to bumper through Logitechs software)

I think you're WAAAAY overthinking this man. They're clearly checking for the delay between press, and release. If they had a way to detect non-hardware clicks, why the fuck would they bother getting the delay lol?

 

Edit: Also, does the on-screen keyboard not use SendInput API...? Of course it does. 

Edit2: Not to mention if they had native code, we'd be able to see it. Kinda hard to hide software being ran on the endusers computer..

 Edited April 17, 2019 by asdttt

---

## [p6] Mold Tester — 2019-04-17T04:14:12Z

This guy has it all figured out!

---

## [p6] asdttt — 2019-04-17T04:22:27Z

On 4/17/2019 at 6:17 AM, Alek said:

Ugh... Gary's Hood is literally using SendInput as well. Yes your mouse is detected because you can hook onto the windows hook chain and monitor for input thats generated by a real device vs those injected by application code - aka using SendInput directly like you are.

Mouse Hook: https://docs.microsoft.com/en-us/windows/desktop/api/winuser/ns-winuser-tagmsllhookstruct

Just please stop making these cringey threads, you're really out of your league.

I'll work on a way to remove all flags like LLMHF_INJECTED and so on. There's 100% a way to hide the fact it's an emulated input without the use of a driver

 

Edit: ASSUMING they're using native code to check input that is.. Wouldn't put it past Jagex but I just don't see why they'd bother logging press->release delays when they have a solid way to detect emulated mouse clicks

 Edited April 17, 2019 by asdttt

---

## [p6] asdttt — 2019-04-17T04:25:43Z

On 4/17/2019 at 6:24 AM, Alek said:

I can still find many more ways, now please stop saying that your autoclicker is undetected/hardware emulation because I just showed you how (one easy way) to determine if its software simulated.

Uhh I said it was undetected on the JVM level, which it true. Low level, of course it is detected.. 

 Edited April 17, 2019 by asdttt

---

## [p6] asdttt — 2019-04-17T04:28:45Z

On 4/17/2019 at 6:27 AM, Alek said:

It's very detected on the JVM level, I already told you that you can use JNI/JNA. I don't mean to be offensive or pick on you, but do I need to spoon feed you all the resources? Just because you don't know how something is being detected, doesn't mean its undetected...

........

Woow it's almost as if JNI/JNA calls native code huh?

 Edited April 17, 2019 by asdttt

---

## [p6] asdttt — 2019-04-17T04:35:02Z

On 4/17/2019 at 6:31 AM, Alek said:

.....

Let me simplify this for you: SendInput is detected - you cannot say SendInput is undetected on any f***ing level, JVM, Windows, iPhone, Smart Refigerator. 

Ok but you still need to call native code so chill. JVM alone can't suddenly detect it. You'd need to execute native code, therefor it's not detectable on the JVM it's detectable on lowlevel.

---

## [p6] asdttt — 2019-04-17T04:38:18Z

On 4/17/2019 at 6:36 AM, Alek said:

I gave you one example, did you check the stacktrace (doesn't require "native code")? If so post it and show me.

If I sent input from my autoclicker to a java application, you think it'd be visible on the stacktrace? I'm not exactly sure what I'm suppose to be looking for? 

I'll try it out I guess?

---

## [p6] asdttt — 2019-04-17T04:41:58Z

Unless I misunderstood you, here's the stacktraces: https://pastebin.com/f8qJbbDp

Nothing out of the ordinary.

---

## [p6] asdttt — 2019-04-17T05:03:40Z

The only source I could locate relating to actually detecting virtual mouse clicks on a low level would people on random forums from 4 years ago claiming there was a "Jaclib.dll" file included in OSRS which apparently contains a check for the mouse. However, it's clearly unfinished and the callback is empty so they appear to have never implemented it. Like I said above, certain mice allow programming keys which use SendInput. If they banned soley on SendInput there'd be zero bypassers, no autoclick bypassing, and most likely false bans from people using software for clicking (More common then you think..). 

 

Info on it:  https://villavu.com/forum/showthread.php?t=115467&p=1367229#post1367229

Here's apparently a bypass too: https://villavu.com/forum/showthread.php?t=115467&p=1367229#post1367229

 

 Edited April 17, 2019 by asdttt

---

## [p6] asdttt — 2019-04-17T05:15:33Z

On 4/17/2019 at 7:08 AM, Alek said:

 

You. Are. Using. The. Same. Windows API. Functions. That. Gary's. Hood. Is. Using. Stop. Calling. Your. Autoclicker. Stealthy. Or. Undetected.

I never claimed it's undetected, simply that it'd be undetected from high level language. OF COURSE IT IS DETECTED FROM NATIVE CODE. IM NOT DENYING THAT? Let's get off the topic of my fucking autoclicker lmao

And why'd you ignore everything I said, including the fact that the only native code they HAD (I can't find any trace of it anymore), contained an empty callback..? Also the fact that people have already found ways to mask the detection..

 

Edit: HOWEVER, Gary's hood is VERY EASILY detectable from  java code.. That was my point... 

 

Edit2: And let's go by this. There's ZEROO evidence Jagex checks mouse clicks from a low level point. BUT, there is evidence they do from a HIGH level point. That point being the delay between press and release, and a few other minor details. 

Please give me evidence that Jagex checks for emulated mouse clicks please since you're clearly one of the best programmers to have ever lived 

 Edited April 17, 2019 by asdttt

---

## [p6] asdttt — 2019-04-17T05:19:47Z

On 4/17/2019 at 7:18 AM, Alek said:

YOU

AND

GARY'S HOOD

USE

THE

SAME 

CODE

WHICH 

IS

SENDINPUT


AM I SPEAKING IN PORTUGESE?

Expand

No but you obviously have a severe reading disability. There's no proof they check for sendInput, only mouse press->release and other JAVA mouse events. Gary's hood has a delay of 0 MS. As simple as that

 

Here, I'll just repost it. Read it slowly 

 

Argument #1:

There's ZEROO evidence Jagex checks mouse clicks from a low level point. BUT, there is evidence they do from a HIGH level point. That point being the delay between press and release, and a few other minor details.  (Which the majority of autoclickers have a delay of 0)

Proof: https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3371

Please give me evidence that Jagex checks for emulated mouse clicks please since you're clearly one of the best programmers to have ever lived. 

 

Argument #2: 

There's literally already a hook to hide indication of the mouse emulation (Multiple on the forum, just look around): https://villavu.com/forum/showthread.php?t=115467&p=1367229#post1367229

 

 Edited April 17, 2019 by asdttt

---

## [p6] asdttt — 2019-04-18T00:04:05Z

On 4/17/2019 at 4:35 PM, Alek said:

Your. Autoclicker. Is. The. Same. As. Most. Autoclickers. You. Are. Using. SendInput.

No. shit. Do you expect me to use a driver? Create something to send inputs from an actual device from the usb? And why bother when there's no evidence they check for sendInput lmao.

Kinda seems you're a bit triggered that the bot you spent years working on* can be detected with little effort lmao. Can't even put together a reasonable response with proof. But keep going off topic, whatever makes you feel better

 Edited April 18, 2019 by asdttt

---

## [p6] Night — 2019-04-18T00:18:09Z

On 4/18/2019 at 2:04 AM, asdttt said:

No. shit. Do you expect me to use a driver? Create something to send inputs from an actual device from the usb? And why bother when there's no evidence they check for sendInput lmao.

Kinda seems you're a bit triggered that the bot you spent years making can be detected with little effort lmao. Can't even put together a reasonable response with proof. But keep going off topic, whatever makes you feel better

Alek didn't make OSBot, just btw

---

## [p6] asdttt — 2019-04-18T00:21:41Z

On 4/18/2019 at 2:18 AM, Night said:

Alek didn't make OSBot, just btw

Your. Signature. Is. The. Same. As. Most. Signatures. You. Are. Using. .GIF.

---

## [p6] asdttt — 2019-04-18T00:40:02Z

On 4/18/2019 at 2:26 AM, Malcolm said:

 

 

Does anybody have proof to what Jagex does/doesn't gather?
 

Yeah tons of proof all over the internet lol. I've even linked the code directly. I mean that's the first step in making a bot, figuring out what you need to spoof. So it's a very talked about subject. Although there's no proof or indication they load any .dll file with the capability of tracking emulated mouse click flags. 

They also have said that you ARE allowed to re-map mouse keys. You are allowed to remap your mouse key to your keyboard key, which would then use SendInput API. Like I told Alek, my mouse is broken from being dropped, so that's literally my only option until I get a new mouse. It'd be ridiculous for them to ban over re-mapped keys. Go look around on youtube, it's a VERRRRY popular thing to remap your mouse to your keyboard for efficiency.

However, if they do detect emulated mouse clicks, then all of this botting is actually secretly allowed by Jagex, but they don't allow you to abuse it too long. It'd also pick up on macro recording, which that one guy with all 99's bypasses with (Too lazy to link, but I think you know). No other explanation really..

---

## [p6] asdttt — 2019-04-18T01:11:20Z

On 4/18/2019 at 2:47 AM, Malcolm said:

Ok let me rephrase this.

Does anybody have evidence to everything Jagex is using/gathering/analyzing for their bot detection methods?

This part needs to stop because we do not know what Jagex's bot detection methods are.

For all we know Jagex has been able to factor in SendInput to a level where they can determine if a legitimate player is using it or a bot is using it.

 

Huh......? We can figure out what data we're sending them................................ It doesn't matter if they use it for their anti-bot or not.................................................................. And yeah, maybe they do have a SendInput check somewhere, but I or anyone else sure as hell can't find it yet. I'll keep probing though..

---

## [p7] Tesh — 2019-04-18T01:22:10Z

On 4/7/2019 at 11:14 AM, asdttt said:

Look man. If you don't believe anything I'm saying about bypassing, then just leave the topic. All your doing is attacking me with assumptions, which I understand, i too would be suspicious of someone who claims to have magically bypassed bans by simply using alternative mouse movement. I don't blame you. 

And I understand your point that mouse movement isn't the biggest factor, nowhere did I claim it is actually. For me, it made the difference between bot freedom and daily bans, but maybe I'm just a lying cunt wasting everyone's time. I get it. 

But here I've served you proof of this flawed mouse movement that is absolutely picked up by heuristics. Now you can know for certainty that they have one solid link to detect OSBot, and now the dev's will be able to target it and hopefully fix it (I actually provided a possible solution, but whether or not he think's it'll work out for OSBot is another story). 

 

Imho, injection client isn't detected as a bot. Jagex has said themselves using third party clients is perfectly fine, wont lead to a ban, and their code shows no sign of code that would be used to detect whether a user is using a properly created bot client,  nor do they have any streamlined classloading that could inject such a thing during runtime. Now, do they detect whether you're using an unofficial client? Possibly?

Expand

As far as clients go, they can detect you are using software, they can't go as far as to detect exactly what client you are using. And as far as ive been told/heard about detection systems, mouse movement is small part or how they go about detecting bots. Other factors possibly include run time, botting hotspots, age of account, flagged IP's, similar names of bot accounts.

Its pretty hard to replicate both humanlike mouse movements and botlike movements, there isnt really a way to decern which is which in my opinion. I do some weird ass mouse movements that other people don't, I click on random tabs for giggles, I examine stuff but dont read any of it.

So in an honest opinion, I highly doubt OSBot's or any other bots mouse movement is massively detected due to the fact its hard to replicate both sides.

---

## [p7] asdttt — 2019-04-18T01:29:48Z

On 4/18/2019 at 3:22 AM, Tesh said:

As far as clients go, they can detect you are using software, they can't go as far as to detect exactly what client you are using. And as far as ive been told/heard about detection systems, mouse movement is small part or how they go about detecting bots. Other factors possibly include run time, botting hotspots, age of account, flagged IP's, similar names of bot accounts.

Its pretty hard to replicate both humanlike mouse movements and botlike movements, there isnt really a way to decern which is which in my opinion. I do some weird ass mouse movements that other people don't, I click on random tabs for giggles, I examine stuff but dont read any of it.

So in an honest opinion, I highly doubt OSBot's or any other bots mouse movement is massively detected due to the fact its hard to replicate both sides.

It's not  really a matter of what the mouse movement means for anti-bot. 

Here's what we know:

1) They're sent the mouse movement

2) OSBot's mouse movement has a few nearly 100% constant flaws in nearly every movement. I've shown a sample that I got from grabbing the same mouse movement, on the same tick as Jagex and simply got the delta between each element to detect the flaws. Mouse movement detection is a lot simpler then you'd think. Hell, you could calculate if the movement has any deviation just by checking angles lol. 

 

If mouse movement is only 1% of their detection, it should still be a priority to fix it. This wont magically solve bans, but it'll be 1 step in the right direction for sure. There's NOTHING we can do to magically solve getting banned because it's no sole check. It's a network of checks, and even what appears to be a TIER'd detection system.

---

## [p7] Tesh — 2019-04-18T01:34:46Z

On 4/18/2019 at 3:29 AM, asdttt said:

It's not  really a matter of what the mouse movement means for anti-bot. 

Here's what we know:

1) They're sent the mouse movement

2) OSBot's mouse movement has a few nearly 100% constant flaws in nearly every movement. I've shown a sample that I got from grabbing the same mouse movement, on the same tick as Jagex and simply got the delta between each element to detect the flaws. Mouse movement detection is a lot simpler then you'd think. Hell, you could calculate if the movement has any deviation just by checking angles lol. 

 

If mouse movement is only 1% of their detection, it should still be a priority to fix it. This wont magically solve bans, but it'll be 1 step in the right direction for sure. There's NOTHING we can do to magically solve getting banned because it's no sole check. It's a network of checks, and even what appears to be a TIER'd detection system. 

Expand

On your second point, im sure the mouse movements do have flaws, as like i said, its hard to replicate either side of the coin, human or bot like. Im no coder or programmer but im sure to make it flawless would take more time then putting up with a 1% ban rate.

As for your first point, If they do get sent mouse movements (which im sure they do), they would probably struggle to determine human or bot from just that alone, which is why all the other sets of information come in more so then mouse info.

---

## [p7] asdttt — 2019-04-18T01:38:04Z

On 4/18/2019 at 3:34 AM, Tesh said:

On your second point, im sure the mouse movements do have flaws, as like i said, its hard to replicate either side of the coin, human or bot like. Im no coder or programmer but im sure to make it flawless would take more time then putting up with a 1% ban rate.

As for your first point, If they do get sent mouse movements (which im sure they do), they would probably struggle to determine human or bot from just that alone, which is why all the other sets of information come in more so then mouse info.

Well I can tell you didn't read the initial post otherwise you would of seen all the proof I've already posted... They send mouse movement, I posted their code that's on the client for that. It's also a defined packet, and is required if you decide to make a custom server. 

If you saw the mouse movement samples I posted, then you could see that they're very visible flaws and shouldn't take long to fix.

---

## [p7] BiggBoss420 — 2019-04-18T08:11:38Z

This is indeed a little disheartening to see, to be honest I didn't read through the github sample's to see how the code is getting sent, and I have no idea about what SendInput is, but it seems like that argument just distracted from the main point.

 

Anyways, from I take you are saying, cut me some slack here, is that Jagex is taking mouse data collected at like 50ms, they slam that shit together, and then run simple stats on it, you called it mouseDelta or whatever the flip. Why is this unreasonable? I don't understand why this is insane, like if what you are saying is true that it can fit into 1mb for 3 hours, that's not that bad. Right?

 

@Alek you stated previously that bot detection most likely stems from play time, skills trained ect. Wouldn't it be really easy to set a threshold, right, get the least amount of false negatives as possible, then we run the analysis on them to the point where it's like 99.9999% likely they are a bot. Is this unreasonable, why is this impossible. Honestly your responses haven't cleared up anything and your responses to asdttt have been very lackluster. Why wouldn't Jagex do this?

 

I thought I'd look at your initial post

Antiban doesn't matter - plain and simple.

If you do any research into official claims made by Jagex, you can see why. They claim that both autoclickers and simulated mouse keys are detectable, and yes people do get banned for using them. For an autoclicker, the mouse doesn't move at all (don't get me started on pseudo number random generators for sleep time).

 

So you state auto clickers and simulated mouse keys are detectable, maybe they have other behavior that makes them detectable. 

 

Gary's Hood and AutoHotKey are detected, both which use SendInput - which is Windows API. My thoughts are that they are just checking the stacktrace of mouse events and determining their source. 
 

So you think they are using SendInput... Ok that's great, asdttt has laid our proof and evidence towards his theory, you just state this without anything backing it at all. 


Additionally a while back they determined that HD clients are indistinguishable from botting clients, which also makes me believe they are looking at the garbage collector.

 

Ok fair enough maybe they are but that doesn't have to do with the argument at hand



But of course, go play around with antiban like everyone else has for the last 15 years - I'm really pessimistic in your results (nothing personal, but it's really a naiive approach).

 

Then you insult him saying he's taking a naive approach. But isn't that just following Occam's razor?

 

Then later on you guys get into a argument that made me want to jump off a bridge and die, and made me realize that I was wasting my time on a forum when I could be playing awesome games with cool hentai girls that like me for who I am ( btw I am cool and am 21 and can drink and drive [ not the driving part ] )

 

Oh sorry did I get off topic? My bad... Sorry to waste your time anyways...

 

Because you're using SendInput... all Windows API functions can be hooked and detected. Look into JNI/JNA (Java). Please don't say something is undetected/hardware call when you're using a usermode public Windows API function call.

 

You kept saying..

 

Ugh... Gary's Hood is literally using SendInput as well. Yes your mouse is detected because you can hook onto the windows hook chain and monitor for input thats generated by a real device vs those injected by application code - aka using SendInput directly like you are
 

then he gives you a counter argument

 

There's ZEROO evidence Jagex checks mouse clicks from a low level point. BUT, there is evidence they do from a HIGH level point. That point being the delay between press and release, and a few other minor details.  (Which the majority of autoclickers have a delay of 0)

 

Response

 

Your. Autoclicker. Is. The. Same. As. Most. Autoclickers. You. Are. Using. SendInput.

 

 

After that you no longer replied. He gave evidence, counter arguments, and in the end you just said the same thing over and over getting caught up about auto clickers being detectable by SendInput ( Again I don't know what the heck that is ).

 

So to wrap up into a conclusion, can you argue his intial claims atleast can you explain why they are untrue, you just stated that they most likely detect using SendInput, why? He has code, he has evidence, can you give us that. Can you give a counter-argument, because just insulting him and  going haha no stupid it's this other thing, why are you suddenly right? The truth is your not, you've done nothing to back up your claims.

 

The only real arguments you gave were Jagex claimed they can catch autoclickers and simulated mouse keys. He gave responses to this and then you got caught up in the argument about SendInput ( what is that anyways?). 

 

Really what you need to do is disprove or make some sort of counter argument why his data is incorrect, or that Jagex does not use mouse movement as a factor in bot banning. If you are going to say that anti-ban is useless you need to be able to back it up, why should I blindly trust you?

 

Additionally you could make a argument how Jagex detect's SendInput, if they are, is it in the code like asdttt showed with his mouse capturing. I would be more keen to believe you then, because yes, then capturing mouse movements would be pretty useuless if they could just detect fake mouse inputs, then it's pretty easy to detect a bot now isn't it, no need to grab data ect. I get what your going for but you do nothing to help your case at all.

 

Lastly I want to say that if Jagex has the code, why not use it? Maybe they are tracking SendInput, but why not also just use the mouse tracking code as given here, asdttt gave us anecdotal evidence that it worked for him, I mean if you trust him it's pretty likely that it had a effect based on just the statistics he stated, it would be really lucky for some reason for him not to get banned after making only changes to the mouse movement.

 

So I think that's everything I really hope you read what I have said and can try and make me understand your side because honestly I can't help but agree more with asdttt with the evidence, and better arguments laid out in this thread.

 

edit: cut out the meat

 

edit2: Don't hate me please  

 

edit3: Oh and bro I don't think it's a good idea to host RS's decompiled java code on your github, pretty sure they don't like that haha.

 Edited April 18, 2019 by BiggBoss420

---

## [p7] asdttt — 2019-04-18T10:16:08Z

On 4/18/2019 at 10:11 AM, BiggBoss420 said:

This is indeed a little disheartening to see, to be honest I didn't read through the github sample's to see how the code is getting sent, and I have no idea about what SendInput is, but it seems like that argument just distracted from the main point.

 

Just to clearify on the thing about autoclickers.. 

He had claimed that they detected both "Gary's Hood and AutoHotKey", which he then claimed are detected through the usage of windows input API. My tests however showed that both those autoclickers had a major flaw, that being that they had 0 delay from mouse click, to release. Which is impossible on a normal mouse, which i provided some proof samples for too. I then showed the code that Jagex uses to both log, then send the mouse press->release delays to the server. It's an INCREDIBLY common anti-cheat check and I'm not surprised Jagex uses it. I then made an autoclicker, and corrected the flaw stating that it'll no longer be detectable on the JVM level (Which runescape's ran on, and assuming they don't load any code that could hook into windows API - which there's zero evidence of). So there is clear proof that Jagex could easily detect BOTH those autoclickers through means of their press->release timing logging.. Unless he's able to get banned using my autoclicker which fixes that issue, I don't think they use Inputevent's.  I ran it for the night on my "main" (Only 3 weeks old  ), using it to splash on a rat to get some easy magic XP. So about 7-8 hours of runtime and no ban - yet. And yes, my autoclicker still used InputEvent.

Alek's absolutely correct by saying it is possible to detect emulated clicks/mouse on from low level code, but it's still possible to hide the flags using a hook, which I posted one that was already made for evidence (There's also ways to spoof a hardware click, but it's not easy). That would also only work for windows, and they'd need a different check for OS's like Linux since it's different API. I had never really thought of them using native code to escape the VM so it's a new thing I'll just need to search for and I'm glad he pointed that out. 

Maybe just a misunderstanding, maybe he just fucking hates my autoclicker lmao

 Edited April 18, 2019 by asdttt

---

## [p7] Impensus — 2019-04-18T10:38:36Z

On 4/18/2019 at 10:11 AM, BiggBoss420 said:

This is indeed a little disheartening to see, to be honest I didn't read through the github sample's to see how the code is getting sent, and I have no idea about what SendInput is, but it seems like that argument just distracted from the main point.

 

Anyways, from I take you are saying, cut me some slack here, is that Jagex is taking mouse data collected at like 50ms, they slam that shit together, and then run simple stats on it, you called it mouseDelta or whatever the flip. Why is this unreasonable? I don't understand why this is insane, like if what you are saying is true that it can fit into 1mb for 3 hours, that's not that bad. Right?

 

@Alek you stated previously that bot detection most likely stems from play time, skills trained ect. Wouldn't it be really easy to set a threshold, right, get the least amount of false negatives as possible, then we run the analysis on them to the point where it's like 99.9999% likely they are a bot. Is this unreasonable, why is this impossible. Honestly your responses haven't cleared up anything and your responses to asdttt have been very lackluster. Why wouldn't Jagex do this?

 

I thought I'd look at your initial post

Antiban doesn't matter - plain and simple.

If you do any research into official claims made by Jagex, you can see why. They claim that both autoclickers and simulated mouse keys are detectable, and yes people do get banned for using them. For an autoclicker, the mouse doesn't move at all (don't get me started on pseudo number random generators for sleep time).

 

So you state auto clickers and simulated mouse keys are detectable, maybe they have other behavior that makes them detectable. 

 

Gary's Hood and AutoHotKey are detected, both which use SendInput - which is Windows API. My thoughts are that they are just checking the stacktrace of mouse events and determining their source. 
 

So you think they are using SendInput... Ok that's great, asdttt has laid our proof and evidence towards his theory, you just state this without anything backing it at all. 


Additionally a while back they determined that HD clients are indistinguishable from botting clients, which also makes me believe they are looking at the garbage collector.

 

Ok fair enough maybe they are but that doesn't have to do with the argument at hand



But of course, go play around with antiban like everyone else has for the last 15 years - I'm really pessimistic in your results (nothing personal, but it's really a naiive approach).

 

Then you insult him saying he's taking a naive approach. But isn't that just following Occam's razor?

 

Then later on you guys get into a argument that made me want to jump off a bridge and die, and made me realize that I was wasting my time on a forum when I could be playing awesome games with cool hentai girls that like me for who I am ( btw I am cool and am 21 and can drink and drive [ not the driving part ] )

 

Oh sorry did I get off topic? My bad... Sorry to waste your time anyways...

 

Because you're using SendInput... all Windows API functions can be hooked and detected. Look into JNI/JNA (Java). Please don't say something is undetected/hardware call when you're using a usermode public Windows API function call.

 

You kept saying..

 

Ugh... Gary's Hood is literally using SendInput as well. Yes your mouse is detected because you can hook onto the windows hook chain and monitor for input thats generated by a real device vs those injected by application code - aka using SendInput directly like you are
 

then he gives you a counter argument

 

There's ZEROO evidence Jagex checks mouse clicks from a low level point. BUT, there is evidence they do from a HIGH level point. That point being the delay between press and release, and a few other minor details.  (Which the majority of autoclickers have a delay of 0)

 

Response

 

Your. Autoclicker. Is. The. Same. As. Most. Autoclickers. You. Are. Using. SendInput.

 

 

After that you no longer replied. He gave evidence, counter arguments, and in the end you just said the same thing over and over getting caught up about auto clickers being detectable by SendInput ( Again I don't know what the heck that is ).

 

So to wrap up into a conclusion, can you argue his intial claims atleast can you explain why they are untrue, you just stated that they most likely detect using SendInput, why? He has code, he has evidence, can you give us that. Can you give a counter-argument, because just insulting him and  going haha no stupid it's this other thing, why are you suddenly right? The truth is your not, you've done nothing to back up your claims.

 

The only real arguments you gave were Jagex claimed they can catch autoclickers and simulated mouse keys. He gave responses to this and then you got caught up in the argument about SendInput ( what is that anyways?). 

 

Really what you need to do is disprove or make some sort of counter argument why his data is incorrect, or that Jagex does not use mouse movement as a factor in bot banning. If you are going to say that anti-ban is useless you need to be able to back it up, why should I blindly trust you?

 

Additionally you could make a argument how Jagex detect's SendInput, if they are, is it in the code like asdttt showed with his mouse capturing. I would be more keen to believe you then, because yes, then capturing mouse movements would be pretty useuless if they could just detect fake mouse inputs, then it's pretty easy to detect a bot now isn't it, no need to grab data ect. I get what your going for but you do nothing to help your case at all.

 

Lastly I want to say that if Jagex has the code, why not use it? Maybe they are tracking SendInput, but why not also just use the mouse tracking code as given here, asdttt gave us anecdotal evidence that it worked for him, I mean if you trust him it's pretty likely that it had a effect based on just the statistics he stated, it would be really lucky for some reason for him not to get banned after making only changes to the mouse movement.

 

So I think that's everything I really hope you read what I have said and can try and make me understand your side because honestly I can't help but agree more with asdttt with the evidence, and better arguments laid out in this thread.

 

edit: cut out the meat

 

edit2: Don't hate me please  

 

edit3: Oh and bro I don't think it's a good idea to host RS's decompiled java code on your github, pretty sure they don't like that haha.

Expand

Lmao were at a time and place where an Osbot dev is getting called out by greys. He knows his stuff and doesn't have to provide 'evidence' to explain this topic to people who don't understand. He has told you about Autoclickers being detected and it's up to you if you accept his claims or not. If you believe otherwise then ignoring the advice of someone behind a large amount of Osbot's history isn't the best direction to go in.

 
On 4/18/2019 at 12:16 PM, asdttt said:

Just to clearify on the thing about autoclickers.. 

He had claimed that they detected both "Gary's Hood and AutoHotKey", which he then claimed are detected through the usage of windows input API. My tests however showed that both those autoclickers had a major flaw, that being that they had 0 delay from mouse click, to release. Which is impossible on a normal mouse, which i provided some proof samples for too. I then showed the code that Jagex uses to both log, then send the mouse press->release delays to the server. It's an INCREDIBLY common anti-cheat check and I'm not surprised Jagex uses it. I then made an autoclicker, and corrected the flaw stating that it'll no longer be detectable on the JVM level (Which runescape's ran on, and assuming they don't load any code that could hook into windows API - which there's zero evidence of). So there is clear proof that Jagex could easily detect BOTH those autoclickers through means of their press->release timing logging.. Unless he's able to get banned using my autoclicker which fixes that issue, I don't think they use Inputevent's.  I ran it for the night on my "main" (Only 3 weeks old  ), using it to splash on a rat to get some easy magic XP. So about 7-8 hours of runtime and no ban - yet. And yes, my autoclicker still used InputEvent.

Alek's absolutely correct by saying it is possible to detect emulated clicks/mouse on from low level code, but it's still possible to hide the flags using a hook, which I posted one that was already made for evidence (There's also ways to spoof a hardware click, but it's not easy). That would also only work for windows, and they'd need a different check for OS's like Linux since it's different API. I had never really thought of them using native code to escape the VM so it's a new thing I'll just need to search for and I'm glad he pointed that out. 

Maybe just a misunderstanding, maybe he just fucking hates my autoclicker lmao

Expand

From my interpretation Alek was specifying that there was more ways to detect the autoclicker than you had mentioned and that all of these would have to be accounted for before you claim your autoclicker is 'undetected' as theoretically Jagex could use any single one of these methods to detect the autoclicker.

 Edited April 18, 2019 by Impensus

---

## [p7] asdttt — 2019-04-18T10:41:29Z

On 4/18/2019 at 12:38 PM, Impensus said:

Lmao were at a time and place where an Osbot dev is getting called out by greys. He knows his stuff and doesn't have to provide 'evidence' to explain this topic to people who don't understand. He has told you about Autoclickers being detected and it's up to you if you accept his claims or not. If you believe otherwise then ignoring the advice of someone behind a large amount of Osbot's history isn't the best direction to go in.

Gray's gotta stick together

#GrayPride

---

## [p7] Impensus — 2019-04-18T10:43:42Z

On 4/18/2019 at 12:41 PM, asdttt said:

Gray's gotta stick together

#GrayPride

It's good you guys are actively pushing towards making Osbot a better client and the research etc your doing is great. From my experience here on Osbot I would definitely take Alek's advice he knows his stuff.

---

## [p7] asdttt — 2019-04-18T10:44:00Z

On 4/18/2019 at 12:38 PM, Impensus said:

Lmao were at a time and place where an Osbot dev is getting called out by greys. He knows his stuff and doesn't have to provide 'evidence' to explain this topic to people who don't understand. He has told you about Autoclickers being detected and it's up to you if you accept his claims or not. If you believe otherwise then ignoring the advice of someone behind a large amount of Osbot's history isn't the best direction to go in.

From my interpretation Alek was specifying that there was more ways to detect the autoclicker than you had mentioned and that all of these would have to be accounted for before you claim your autoclicker is 'undetected' as theoretically Jagex could use any single one of these methods to detect the autoclicker.

Yeah and he's absolutely right, but I never claimed it was undetectable from outside the JVM. If they execute native code to detect my input, then it's no longer inside the JVM and would be OS specific. That's allll I meant. 

But hey, at least I didn't create a resource injector and call it "Stealth Injection" - which WOULD be detectable from the JVM. 

 
On 4/18/2019 at 12:43 PM, Impensus said:

It's good you guys are actively pushing towards making Osbot a better client and the research etc your doing is great. From my experience here on Osbot I would definitely take Alek's advice he knows his stuff.

Come on man. He's an orange. Can't trust 'em

 Edited April 18, 2019 by asdttt

---

## [p7] BiggBoss420 — 2019-04-18T19:16:17Z

On 4/18/2019 at 12:38 PM, Impensus said:

Lmao were at a time and place where an Osbot dev is getting called out by greys. He knows his stuff and doesn't have to provide 'evidence' to explain this topic to people who don't understand. He has told you about Autoclickers being detected and it's up to you if you accept his claims or not. If you believe otherwise then ignoring the advice of someone behind a large amount of Osbot's history isn't the best direction to go in.

From my interpretation Alek was specifying that there was more ways to detect the autoclicker than you had mentioned and that all of these would have to be accounted for before you claim your autoclicker is 'undetected' as theoretically Jagex could use any single one of these methods to detect the autoclicker.

I don't understand... EXACTLY, you just answered my question, I should blindly trust someone. In case you didn't know that's a common fallacy people fall into as a trap ( Blind Loyalty). And you know what you are right he doesn't have to explain himself, but it looks like he tried to. Anyways I don't have a negative opinion of him, actually, I think he's probably fairly intelligent( I like OS Bot and have been writing bots from time to time, and come across his posts every once in a while and they are usually helpful ), he just can't explain himself clearly, but that doesn't mean he's not correct. But in order to convince someone like me, a position of superiority doesn't just do the trick.

 

Maybe there is a post you could point me in, and be like "haha stupid idiot, we answered this already in X", and you know what I would do, I would read that, and if I was happy with the answers laid out there I would be done and agree, and go, "oh mouse movement really doesn't matter.", But I don't know about any post at any time in history. Why does it matter if he's been actively protesting anti-ban for 15 years, if he never made a argument as to why it doesn't work, it doesn't matter how long he's been doing it, that doesn't make him right (The Appeal to Tradition)

 

Also sorry for using logical fallacies, it's a little dumb, and you can probably point out 20 in me, but I just wanted to explain my side and why I disagreed. See I explained, now you can understand me, but @Alek hasn't.

 

And yeah, you are right, that's what he said, he stated that SendInput was detectable, but that didn't answer the question at hand, read my initial post I explain this.

 
On 4/18/2019 at 5:50 PM, Alek said:

Once again, your code is detectable from "within the JVM" as you put it. It's called stealth injector because the injector itself is not detected - it's to quell people in the Reflection/Injection arguments. 

Yes, I'm way more qualified to talk about detection and hacking than you. I wrote public aimbots and sold them for 2 years (Paladins, CSGO, Day of Infamy - all with my own custom updaters and AOB scanners), I'm fluent in MASM32 (x86 assembly), and I've disassembled more PEs, bypassing more DRMs and anticheat software than you have.

Your best "bypass" is writing a 6 line C++ autoclicker that uses SendInput - which doesn't reverse anything and uses the same exact public Windows API functions that other Windows autoclickers use. You could have just wrote your code in C# or VBA. Please stop saying it's "not detected by the JVM" - you have not a clue what you are talking about. 

Just because you can't think of way it can be detected, doesn't mean it's undetected. You're an absolute joke.

 

@Malcolm

Yes, they publicly post what can be detected. asdttt is illiterate so he there's no way he would read this and understand what it means in the context of his C# autoclicker:
https://secure.runescape.com/m=news/mouse-keys---changes--clarification?oldschool=1

Expand

 

Yikes, that is not a good response, can't you see what you are saying. 

Yes, I'm way more qualified to talk about detection and hacking than you. I wrote public aimbots and sold them for 2 years (Paladins, CSGO, Day of Infamy - all with my own custom updaters and AOB scanners), I'm fluent in MASM32 (x86 assembly), and I've disassembled more PEs, bypassing more DRMs and anticheat software than you have.

= 

What the fuck did you just fucking say about me, you little bitch? I’ll have you know I graduated top of my class in the Navy Seals, and I’ve been involved in numerous secret raids on Al-Quaeda, and I have over 300 confirmed kills. I am trained in gorilla warfare and I’m the top sniper in the entire US armed forces. You are nothing to me but just another target. I will wipe you the fuck out with precision the likes of which has never been seen before on this Earth, mark my fucking words. You think you can get away with saying that shit to me over the Internet? Think again, fucker. As we speak I am contacting my secret network of spies across the USA and your IP is being traced right now so you better prepare for the storm, maggot. The storm that wipes out the pathetic little thing you call your life. You’re fucking dead, kid. I can be anywhere, anytime, and I can kill you in over seven hundred ways, and that’s just with my bare hands. Not only am I extensively trained in unarmed combat, but I have access to the entire arsenal of the United States Marine Corps and I will use it to its full extent to wipe your miserable ass off the face of the continent, you little shit. If only you could have known what unholy retribution your little “clever” comment was about to bring down upon you, maybe you would have held your fucking tongue. But you couldn’t, you didn’t, and now you’re paying the price, you goddamn idiot. I will shit fury all over you and you will drown in it. You’re fucking dead, kiddo.

 

Honestly the resemblance is uncanny, anyone reading this that is okay with this should read up on Cognitive bias, this is no way anyone with any sense of power, or a trusted member on a forum should treat someone who has only been trying to help. That's the sad part, he's only trying to help the community, he's not attacking anyone, he could of just left and then nothing would of happened, and by posting something like this you may of just cemented your goal.

 

You should be happy that people are testing your knowledge, not upset that they don't blindly trust you. Blind trust leads to the downfall of so many great communities, and it's sad to see that happening here...

 

And then you actually make a decent argument, giving evidence as to why you think SendInput is detectable by Jagex, let me outline.

1. Sendinput is used in other windows auto-clickers

2. Other windows autoclickers are detectable by Jagex

3. Sendinput is detectable

 

A decent argument, and with that Jagex post, honestly you might be right. But if I don't understand there were some points that asdttt made about delay playing a factor, and that Jagex can't poll that data from your mouse. If you could show me evidence that Jagex could find SendInput ( write a code snippet yourself, or link to one, find in the game code, or other ), then I would be 100% more keen to agree with you.

 

But guess what that doesn't ANSWER THE QUESTION AT HAND.

 

Why are mouse movement's futile, why should I blindly trust you o great one. Why does jagex have all this code, just to throw ignorant people like me off track? Can you answer this, because at this point it looks like you've given up and just expect us to agree based on your superiority.

 

 

 Edited April 18, 2019 by BiggBoss420

---

## [p7] asdttt — 2019-04-18T19:25:50Z

On 4/18/2019 at 5:50 PM, Alek said:

Once again, your code is detectable from "within the JVM" as you put it. It's called stealth injector because the injector itself is not detected - it's to quell people in the Reflection/Injection arguments. 

Yes, I'm way more qualified to talk about detection and hacking than you. I wrote public aimbots and sold them for 2 years (Paladins, CSGO, Day of Infamy - all with my own custom updaters and AOB scanners), I'm fluent in MASM32 (x86 assembly), and I've disassembled more PEs, bypassing more DRMs and anticheat software than you have.

Your best "bypass" is writing a 6 line C++ autoclicker that uses SendInput - which doesn't reverse anything and uses the same exact public Windows API functions that other Windows autoclickers use. You could have just wrote your code in C# or VBA. Please stop saying it's "not detected by the JVM" - you have not a clue what you are talking about. 

Just because you can't think of way it can be detected, doesn't mean it's undetected. You're an absolute joke.

 

@Malcolm

Yes, they publicly post what can be detected. asdttt is illiterate so he there's no way he would read this and understand what it means in the context of his C# autoclicker:
https://secure.runescape.com/m=news/mouse-keys---changes--clarification?oldschool=1

Expand

1) Post a method you can detect it within the JVM using pure javacode. NO cheating ?. It was made to bypass that one check I found within their code, which it does. The same check that just so happens to catch both the programs that you mentioned. Never have I claimed you can't detect it outside of the JVM.. Not sure why I have to keep repeating myself. Within the JAVA VIRTUAL MACHINE, the events are no different to that of a normal mouse. OUTSIDE the java virtual machine, that is not the case. Chill ffs

 

2) Whatever you're injecting could easily be detected, so why does it even matter if the injector it's self is detected lol. 

 

3) "unless it is to remap a key to any other button" - Which would be detected as "emulated". A pretty massive portion of OSRS remaps mouse keys to their keyboard keys for efficiency. Idk if you understand the extent of sendInputEvent's use over various gaming/utility programs. And people to this day still use AHK, or mouse recorder at very low banrates surprisingly. And once again, it's not difficult to mask the fake clicks (Which there's already a hook for). There's other alternatives to spoofing hardware clicks, or even creating your own hardware/driver to send ACTUAL hardware clicks so it'd come directly from the HID stack which would bypass even a rawinput check.

 

If you can provide evidence that they detect SendInputEvent, I will drop my argument completely and leave you be. You claim to be this amazing programmer, so prove it..? 

And do you honestly believe that the only reason OSBot is being detect is based off it's fake clicks....? You really don't think that the mouse movement is flawed? You think as a human, that movement is reproducible? Wtf

-Btw, seen your blog. Nothing a programmer straight out of a university couldn't achieve lol. Tone down your ego

 

 Edited April 19, 2019 by asdttt

---

## [p7] asdttt — 2019-04-19T00:27:30Z

On 4/19/2019 at 1:16 AM, Alek said:

Congrats on single handedly defeating Jagex in a 15 year battle with bots, by using a 4 line C# script. 

Well my shitty C++ autoclicker obviously doesn't bypass lower level checks, but it's still possible. If you came directly from the HID stack, then what can they do? RawInput check? Nope. Just saying it's possible to bypass, not saying I solved it lmao. 

I just couldn't find any evidence that they were using such checks is all I mean.. I mean, can  you..? Has anyone? 

 Edited April 19, 2019 by asdttt

---

## [p7] asdttt — 2019-04-19T00:46:03Z

On 4/19/2019 at 2:42 AM, Alek said:

Explain in one sentence how you are using SendInput more correctly than Gary’s Hood.  Don’t mention pseudo random numbers. 

There's a delay between mouse press and the mouse release event on a normal mouse, 99% of autoclickers such as Gary's Hood execute both events instantly whereas mine delays it within the bounds of a normal mouseclick (Around 50-100MS generally). 

 

Here's their code checking for that: https://github.com/zeruth/runescape-client/blob/master/src/Client.java#L3371

 

Yeah it's a simple thing sure, but it's important.

 

Edit: Wrong code clip posted, I'll find the correct 1

 Edited April 19, 2019 by asdttt

---

## [p7] asdttt — 2019-04-19T01:00:36Z

On 4/19/2019 at 2:49 AM, Alek said:

Delay between the events is nothing new, actually most autoclickers you can specify that amount. OSBot has a delay as well. 

True, but that's just the only thing that popped into my head when thinking about events fed to the JVM. 

I never thought of Jagex deploying anti-cheat similar to EAC so it never crossed my mind they'd check for whether the input was hardware or not. It seems silly that they'd put all this work into their anti-bot and not expand outside of the JVM but I just can't find any leads, and neither can anyone else (Or they're just being silent about it..).  Or even what they'd use to load the native binaries to begin with (Without it being obvious/exposed easily).. 

 

 Edited April 19, 2019 by asdttt

---

## [p8] asdttt — 2019-04-19T01:56:13Z

Going to make a mechanism to physically click my mouse on an interval set by gears. GL Jagex

---

## [p8] Search12 — 2019-04-20T10:33:40Z

On 4/18/2019 at 5:50 PM, Alek said:

Yes, I'm way more qualified to talk about detection and hacking than you. I wrote public aimbots and sold them for 2 years (Paladins, CSGO, Day of Infamy - all with my own custom updaters and AOB scanners), I'm fluent in MASM32 (x86 assembly), and I've disassembled more PEs, bypassing more DRMs and anticheat software than you have.

Got me gut laughing. I was in two minds about joining this discussion since I rarely take a front seat to these types of discussions but this one was way too fucking funny to resist. Shit, you can search memory for an array? OMG, you can use a disassembler?? bypassing DRMs??? Teach me how to be like you bro.

 

 
On 4/17/2019 at 6:07 AM, Alek said:

Because you're using SendInput... all Windows API functions can be hooked and detected. Look into JNI/JNA (Java). Please don't say something is undetected/hardware call when you're using a usermode public Windows API function call.

 
On 4/17/2019 at 6:27 AM, Alek said:

It's very detected on the JVM level, I already told you that you can use JNI/JNA. I don't mean to be offensive or pick on you, but do I need to spoon feed you all the resources? Just because you don't know how something is being detected, doesn't mean its undetected...

........

 
On 4/17/2019 at 6:17 AM, Alek said:

Ugh... Gary's Hood is literally using SendInput as well. Yes your mouse is detected because you can hook onto the windows hook chain and monitor for input thats generated by a real device vs those injected by application code - aka using SendInput directly like you are.

Mouse Hook: https://docs.microsoft.com/en-us/windows/desktop/api/winuser/ns-winuser-tagmsllhookstruct

Just please stop making these cringey threads, you're really out of your league.

Imagine belittling someone but being too ignorant or stupid to actually check if they even use the detection method. There is no low level native mouse or keyboard hook in oldschool runescape for the context of detecting injected input. It simply doesn't exist. Anyone with 5% of the experience you so humbly bragged about could easily check that before making a baseless claim on the detection of SendInput and contributing to the shit show here on this thread. Outstanding job pal. 

Keeping the discussion civil. It doesn't matter if you use SendInput or regular java event dispatching in the context of detecting the validity of input since there's isn't any differing integrity tests on input events anywhere in the client. Bytecode or native. While they're both detectable, neither are being detected. Keynoting that this says nothing about the timings, and delays of those events.

---

## [p8] Norppa — 2019-04-24T15:03:20Z



---

## [p8] Naked — 2019-04-24T18:00:32Z

On 4/20/2019 at 12:33 PM, Search12 said:

Got me gut laughing. I was in two minds about joining this discussion since I rarely take a front seat to these types of discussions but this one was way too fucking funny to resist. Shit, you can search memory for an array? OMG, you can use a disassembler?? bypassing DRMs??? Teach me how to be like you bro.

 

Imagine belittling someone but being too ignorant or stupid to actually check if they even use the detection method. There is no low level native mouse or keyboard hook in oldschool runescape for the context of detecting injected input. It simply doesn't exist. Anyone with 5% of the experience you so humbly bragged about could easily check that before making a baseless claim on the detection of SendInput and contributing to the shit show here on this thread. Outstanding job pal. 

Keeping the discussion civil. It doesn't matter if you use SendInput or regular java event dispatching in the context of detecting the validity of input since there's isn't any differing integrity tests on input events anywhere in the client. Bytecode or native. While they're both detectable, neither are being detected. Keynoting that this says nothing about the timings, and delays of those events. 

Expand

Prove they're not being detected.

---

## [p8] Search12 — 2019-04-24T23:14:04Z

On 4/24/2019 at 8:00 PM, Naked said:

Prove they're not being detected.

You want me to provide proof of something that isn't there? Here, i'll just teach you how to do you yourself.

Write a SetWindowsHookEx hook/detour and place it in a DLL which installs our hook in DLLMain. You can copy and paste any basic 32bit trampoline function. WinAPI functions all come with a standard useless 2 byte hotpatch point (mov edi, edi). This includes SetWindowsHookEx. The hook will notify us if any process (which has our DLL loaded into it's address space) invokes SetWindowsHookEx.
Start oldschool runescape in a suspended state. Inject your DLL. Resume oldschool runescape so it is no longer in a suspended state. If the process calls SetWindowsHookEx then it has tried to create a hook. That's how you would detect windows hooks. 

Extra: You can grab the params which have been passed to SetWindowsHookEx. Including a function pointer and manually navigate to the code in memory (can use any decompiler) if you happen to find that hooks have been installed.

---

## [p8] asdttt — 2019-04-25T22:18:22Z

On 4/25/2019 at 1:14 AM, Search12 said:

You want me to provide proof of something that isn't there? Here, i'll just teach you how to do you yourself.

Write a SetWindowsHookEx hook/detour and place it in a DLL which installs our hook in DLLMain. You can copy and paste any basic 32bit trampoline function. WinAPI functions all come with a standard useless 2 byte hotpatch point (mov edi, edi). This includes SetWindowsHookEx. The hook will notify us if any process (which has our DLL loaded into it's address space) invokes SetWindowsHookEx.
Start oldschool runescape in a suspended state. Inject your DLL. Resume oldschool runescape so it is no longer in a suspended state. If the process calls SetWindowsHookEx then it has tried to create a hook. That's how you would detect windows hooks. 

Extra: You can grab the params which have been passed to SetWindowsHookEx. Including a function pointer and manually navigate to the code in memory (can use any decompiler) if you happen to find that hooks have been installed.

Expand

Would still be detectable as it's not coming off the HWID stack, but that's if they even have the anti-cheat measures to check that. Still wouldn't be impossible to bypass. Hell, you could make an autoclicker in Logitech's driver software and it'd come clean off the HWID stack. 

IMO, I think Jagex understands that botting is simply a part of the game at this point. They're getting little to no new players, RS3 is pretty much dead, and OSRS is full of autoclickers. It makes zero sense why I can bot on the most blatant script ever made for 4 hours straight and get no ban. ZERO sense. To me, it feels like they actually just allow micro-botting as long as it wont harm the economy. This game is so repetitive, if you actually took the time to train your strength or what not to 99 MANUALLY then you should probably get a job lol. 

And from what it appears during my testing, they've done nothing with windows API. They do have code to download jars and load .dll files, but I haven't been able to detect anything while botting. Maybe they load it after 6-10 hours of straight botting for a bit, then unload it? I've got no idea. I got 75 magic about 2 days ago by autoclicking alch for like 9 hours straight and got no ban either. It's been theorized by most botters that their system is tiered, so this would certainly back that claim up. Maybe detecting clicks is the very top tier of detection, their last ditch effort. That would also mean that fixing other flaws such as the mouse movement would further delay bans, therefor, in theory, allowing for a much longer botting period.

---

## [p8] Search12 — 2019-04-26T01:02:56Z

On 4/26/2019 at 12:18 AM, asdttt said:

Would still be detectable as it's not coming off the HWID stack, but that's if they even have the anti-cheat measures to check that. Still wouldn't be impossible to bypass. Hell, you could make an autoclicker in Logitech's driver software and it'd come clean off the HWID stack. 

Are you keeping up with the flow of the conversation? I was stating why Alek's point is bad because they don't do that specific type of detection (hooks through SetWindowsHookEx) and Naked asked me for proof. It has nothing to do with other types of detection or other types of input. All types of input are detectable but not being detected (Integrity isn't being tested anywhere). Logitech's driver software isn't a MouClass.sys replacement. It's what you would call a filter driver. Lives at a higher level than MouClass.sys but it is still under Win32K.sys so it is in kernel space. All these methods are easily detectable unless your AC is running in userland. I will state it again; This type of detection doesn't exist anywhere in the game client. They don't test the integrity of mouse/key events anywhere in the oldschool runescape client.

You can check for yourself but i can tell you that there's nothing nefarious in any native code in oldschool runescape.

 Edited April 26, 2019 by Search12

---

## [p8] asdttt — 2019-04-26T01:38:23Z

On 4/26/2019 at 3:02 AM, Search12 said:

Are you keeping up with the flow of the conversation? I was stating why Alek's point is bad because they don't do that specific type of detection (hooks through SetWindowsHookEx) and Naked asked me for proof. It has nothing to do with other types of detection or other types of input. All types of input are detectable but not being detected (Integrity isn't being tested anywhere). Logitech's driver software isn't a MouClass.sys replacement. It's what you would call a filter driver. Lives at a higher level than MouClass.sys but it is still under Win32K.sys so it is in kernel space. All these methods are easily detectable unless your AC is running in userland. I will state it again; This type of detection doesn't exist anywhere in the game client. They don't test the integrity of mouse/key events anywhere in the oldschool runescape client.

You can check for yourself but i can tell you that there's nothing nefarious in any native code in oldschool runescape.

I was just pointing out that they could achieve nearly the same level of detection based on raw input, so you wouldn't be able to detect that using the method you described. 

And yeah I couldn't find anything interesting either, but as I described above, the detection appears to be tiered. Quoting myself:

 
On 4/26/2019 at 12:18 AM, asdttt said:

And from what it appears during my testing, they've done nothing with windows API. They do have code to download jars and load .dll files, but I haven't been able to detect anything while botting. Maybe they load it after 6-10 hours of straight botting for a bit, then unload it? I've got no idea. I got 75 magic about 2 days ago by autoclicking alch for like 9 hours straight and got no ban either. It's been theorized by most botters that their system is tiered, so this would certainly back that claim up. Maybe detecting clicks is the very top tier of detection, their last ditch effort. That would also mean that fixing other flaws such as the mouse movement would further delay bans, therefor, in theory, allowing for a much longer botting period. ﻿

Expand

 

So in other words, I don't believe they use this level of detection. If they do, then they're very very sneaky with it and it's injected only after botting a certain amount of time, and only for a certain length of time. Like a "last ditch effort" as I described above.

 

Edit: Also I forgot to mention, there actually was a .dll file called "jaclib.dll" or whatever that contained code to catch the API input flag "LLMHF_INJECTED" (From sendInput) - although there was never a callback implemented. Weird... Very weird.. Especially since they bothered to ship it... Maybe because they realized that programmers could easily remove that flag through a hook + trampoline function, or by just unregistering it lmao (Through WH_DEBUG). They appear to have removed it though. 

 Edited April 26, 2019 by asdttt

---

## [p8] Turkoize — 2019-04-26T01:43:05Z

I literally have no idea what any of this code talk means but it’s entertaining watching you all go at it, keep it up

 Edited April 26, 2019 by Turkoize

---

## [p8] Search12 — 2019-04-26T02:28:58Z

On 4/26/2019 at 3:38 AM, asdttt said:

I was just pointing out that they could achieve nearly the same level of detection based on raw input, so you wouldn't be able to detect that using the method you described. 

And yeah I couldn't find anything interesting either, but as I described above, the detection appears to be tiered. Quoting myself:

 

So in other words, I don't believe they use this level of detection. If they do, then they're very very sneaky with it and it's injected only after botting a certain amount of time, and only for a certain length of time. Like a "last ditch effort" as I described above.

 

Edit: Also I forgot to mention, there actually was a .dll file called "jaclib.dll" or whatever that contained code to catch the API input flag "LLMHF_INJECTED" (From sendInput) - although there was never a callback implemented. Weird... Very weird.. Especially since they bothered to ship it... Maybe because they realized that programmers could easily remove that flag through a hook + trampoline function, or by just unregistering it lmao (Through WH_DEBUG). They appear to have removed it though. 

Expand

My own belief is that while things like third-party clients probably do contribute; detection is massively based on whether your account has similarities to other accounts (including banned accounts) - basic machine learning. Similarities including but not limited to delays and timings, account progression and so on. Reports probably trigger the server side AC system. Maybe large disparity trades do too.

Is common information at SRL and has been for years. There was never a dll called jaclib.dll in oldschool runescape. That one only exists in RS3 so they couldn't remove it from oldschool since it never existed there to begin with. NXT actually has hard detection. jaclib.dll still exists in rs3 java client last i checked.

---

## [p8] asdttt — 2019-04-26T02:39:10Z

On 4/26/2019 at 4:28 AM, Search12 said:

My own belief is that while things like third-party clients probably do contribute; detection is massively based on whether your account has similarities to other accounts (including banned accounts) - basic machine learning. Similarities including but not limited to delays and timings, account progression and so on. Reports probably trigger the server side AC system. Maybe large disparity trades do too.

Is common information at SRL and has been for years. There was never a dll called jaclib.dll in oldschool runescape. That one only exists in RS3 so they couldn't remove it from oldschool since it never existed there to begin with. NXT actually has hard detection. jaclib.dll still exists in rs3 java client last i checked.

Ah that explains why I couldn't find it. Haven't played RS3 in ages. 

And I agree completely that they use machine learning, or at least pattern matching. Which is why I produced this thread showing how their current logging capabilities could EASILY pickup on OSBot. Every bot client I tried actually has flawed mouse movement patterns (Only tried 2 others). 

It split off into another direction primary because Alek basically said "If it's detectable by x, then why bother fixing y".

Edit: And there's other basic stuff like NEVER loosing keyboard focus, even when the virtual mouse is "moved off the screen" that probably adds to the probability of a user being a bot. 

 Edited April 26, 2019 by asdttt

---

## [p8] Imthabawse — 2019-04-26T02:50:13Z

Love that you guys are actively trying to understand how to prevent botting bans and improve the client and I'm sure there's stuff going on behind the scenes that we don't even know about. With that said I've been botting multiple accounts in moderation for bout two years using OsBot so cheers OsBot keep doing what you do but also hear out the community and try to keep the peace. One love lol.

---

## [p8] adjacent — 2019-04-26T21:29:22Z

This is an interesting topic. If Jagex collects mouse movement - they don't do it "just because", there's a purpose for it, highly likely botwatch related.

If Osbot manages to improve it's mouse movement and eliminate the obvious patterns - this could give a huge 1up against competion. Even if accounts survive a couple days longer with Osbot than with other bots - this will provide a significant business boost as word spreads around.

---

## [p8] caketeaparty — 2019-05-06T14:54:42Z

This is gold. I've been wondering why my mouse recorder used correctly results in virtually 0 bans vs. Injection and even Mirror, even on flagged proxies. By the way, I'm pretty sure the random lag spikes on Mirror skews the mouse data a bit as well, which might be why it tends to result in fewer bans.

They can only reliably get this data on the client-side, right? That means if they use mouse movements as a detection vector (they aren't collecting this data for no reason), this is their only way to do so - transparently. It's kind of odd people are contesting this despite you literally proving it with the deobfuscated code and demonstrating detectable patterns, but at the end of the day...What works is what works.

Honestly, the only ways they can determine you are a bot are:

Your script breaks and gets stuck for hours, dead giveaway.
Creating patterns from your inputs and likely comparing them to other bots.
Detecting scripts/bot client. Everyone claims the client is not detected, but you never know. They don't have to load classes or dlls for this, they can probably determine whether you're using a specific bot client or running a script on a certain client by checking your behaviours against all of the data they've gathered over time, and mouse movement evidently factors into this.

There are plenty of people who play 15 hours a day farming Vorkath or something and never catch a ban, to say that you can never figure out a way to bot that long is obviously wrong. They aren't deciding who is and isn't a bot out of thin air. This is clearly one of the factors if not the most significant one, next to IP type and other patterns, like keyboard patterns if they gather that. After all, you have to move the mouse to play the game at all.

 Edited May 6, 2019 by caketeaparty

---

## [p8] NotInsomnia — 2019-05-06T15:18:06Z

Didn't know they actually tracked mouse movement this way, interesting read

---

## [p9] RoundBox — 2019-05-08T11:47:15Z

@asdttt

 

I'm not sure this is relevant to this topic but I remember years and years ago when botting in OSRS was still in its infancy (first 12 months) there was a client that upon booting up the client, users could opt in to help their mouse algorithm by doing a quick 1 minute clicking test (similar to the ones used to build click accuracy). Once done, it would save a file to the users Documents folder and then there was a thread for the users to upload the document to. 

All of this was used to help develop human mouse movement for their client.

 

If OSbot implemented this, would this solve the problem?

---

## [p9] Probability — 2019-09-16T05:32:38Z

Hi @asdttt . I found your views very interesting. You've provided some evidence that Jagex monitors:

i) Mouse movements.

ii) Jagex would monitor click->release patterns.

iii) Jagex reaction times.

One problem I've noticed in the thread is that the pictures have expired. Would you be able to re-upload them or share in a way I could have a look?

Thanks!

---

## [p9] srhrich — 2019-09-19T15:17:48Z

Can you Pm me

---

## [p9] RSAccountsFarm — 2019-09-21T09:20:54Z

Interesting read. Thanks.

---

## [p9] Chris — 2019-09-22T22:05:25Z

On 4/7/2019 at 8:45 AM, Patrick said:

Not only did I already tell you I believe mouse movement can be used for detection - all be it only a very small part of the system -, I also told you it's something we're interested in changing and are discussing. From months of testing I can confirm that you can bot without getting banned when botting 4+ hours almost everyday, when only using the OSBot API.

any update

---

## [p9] Patrick — 2019-09-23T16:29:34Z

On 9/23/2019 at 12:05 AM, Chris said:

any update

We're still looking into stuff yeah

---

## [p9] Anomaly — 2019-09-23T16:59:54Z

literally been saying this for years lol, good to see forward movement

---

## [p9] z10n — 2019-09-25T16:25:11Z

I think we should gather human data, and re-create it so the bot appears to be the same, at the 50ms intervals.

---

## [p9] mariokiller64 — 2019-12-04T11:12:50Z

Would something like this help at all?

https://github.com/JoonasVali/NaturalMouseMotion
 

Just wondering.

---

## [p9] Eejit — 2019-12-15T11:54:40Z

Is there any way to replace Osbot mouse movement with custom mouse movement without rewriting every single part of the API that contains any mouse movement?

---

## [p9] BuyingHardcores — 2019-12-15T20:50:33Z

damn

---

## [p9] Covid1984 — 2019-12-17T18:58:25Z

.

 Edited February 8, 2024 by Covid1984

---

## [p9] AceKingSuited — 2020-01-14T07:10:34Z

This thread has brought a lot of confirmation to what I've spent the last few weeks mulling over. Even down to the recording of movements to compare against the client's mouse movement and attempting to see if the client's mouse movement can easily be identified. Always suspected mouse movement was sent to the server, but never came across any physical proof until reading this thread.

There's a project we'll call "Cat and Mouse" that I've been thought experimenting with for a while to help with this *exact* problem. While part of me has been wanting to keep it to myself I think I'll share here where it feels most relevant and maybe even open source it at some point if it makes sense to. Recording mouse data on a large scale is difficult (consent, incentive, etc) so the point of this project would be to do it in both an ethical and incentivized manner.

Basically, there are 3 objectives to "Cat and Mouse".

Collect human mouse data (and maybe keyboard or other forms of behavior down the road) via a fun / incentivized game designed to create situations that affect behavior. Some examples would be size of click area, distance of movement, importance of accurate clicks, moving vs fixed targets, whether the movement is rushed, etc
Create a bot that attempts to go undetected playing the game vs that large sample size of human data <---- Mouse
Create a bot detection system that the bot is constantly trying to beat <---- Cat

With a large sample size of human data and several iterations of the bot detection system (via machine learning?) the bot should be able to get better and better at replicating human inputs (mouse, keyboard, etc).

I'm sure there's a lot more to it than just what I've described as "solving the problem" and I'm purposely keeping a lot of the more interesting bits to myself, but coming across this thread kind of sparked the need to share the idea

---

## [p9] BotBotDingDing — 2020-01-15T18:02:18Z

Had been using Czar's perfect fighter for a couple months on and off and got to 78 slayer. Used the free macro recorder for the first time to clean herbs at full speed, took a couple records to get my time down and played for 3hours. Even did a couple quests by hand but got banned the following morning. Should have read this first meh.

---

## [p9] Gunman — 2020-01-20T12:14:41Z

On 4/7/2019 at 8:45 AM, Patrick said:

Not only did I already tell you I believe mouse movement can be used for detection - all be it only a very small part of the system -, I also told you it's something we're interested in changing and are discussing. From months of testing I can confirm that you can bot without getting banned when botting 4+ hours almost everyday, when only using the OSBot API.

 
On 9/23/2019 at 6:29 PM, Patrick said:

We're still looking into stuff yeah

Anything new about this?

---

## [p10] RawrChad — 2020-01-20T15:55:02Z

On 4/7/2019 at 4:48 AM, Night said:

I didn't read the post, but you might want to be careful about posting Jagex's code on your github. They tend not to like people doing things like that (redistributing copy-righted code).

They also do not like people botting their game, selling ingame currency, etc, etc.. What is your point.

---

## [p10] Patrick — 2020-01-21T16:50:18Z

On 1/20/2020 at 1:14 PM, Gunman said:

Anything new about this?

Couple small changes, nothing major yet

---

## [p10] osbot337 — 2020-01-22T06:43:56Z

is it true that the scripts in osbot have no mouse delays in their "clicks".  Meaning that the mouse clicks is happening immediately, not like human mouse which happens in steps like:

"leftbuttondown, 100ms delay, leftbuttonup" which is 1 click.

"click" happens immediently with no delays, in 0ms.

---

## [p10] Gunman — 2020-01-22T06:47:38Z

On 1/22/2020 at 7:43 AM, osbot337 said:

is it true that the scripts in osbot have no mouse delays in their "clicks".  Meaning that the mouse clicks is happening immediately, not like human mouse which happens in steps like:

"leftbuttondown, 100ms delay, leftbuttonup" which is 1 click.

"click" happens immediently with no delays, in 0ms.

If I remember correctly the previous client dev Alek said it does.

---

## [p10] z10n — 2020-03-11T19:40:30Z

I think OSbot could give people credit twords premium scripts in exchange for playing rs2007 normally (without botting) using OSBot, to gather RS2007 mouse movement data.

---

## [p10] Gabriel Ramuglia — 2020-04-22T18:38:57Z

On 12/15/2019 at 12:54 PM, Eejit said:

Is there any way to replace Osbot mouse movement with custom mouse movement without rewriting every single part of the API that contains any mouse movement?

I would like to know this too.

Any starting point for what methods I would need to reimplement, I would greatly appreciate.

 
On 1/22/2020 at 7:43 AM, osbot337 said:

is it true that the scripts in osbot have no mouse delays in their "clicks".  Meaning that the mouse clicks is happening immediately, not like human mouse which happens in steps like:

"leftbuttondown, 100ms delay, leftbuttonup" which is 1 click.

"click" happens immediently with no delays, in 0ms.

Is this true? It seems this would be pretty easy to fix.

---

## [p10] Gabriel Ramuglia — 2020-04-22T18:50:50Z

On 12/4/2019 at 12:12 PM, mariokiller64 said:

Would something like this help at all?

https://github.com/JoonasVali/NaturalMouseMotion
 

Just wondering.

Thanks for the link. I'll see if I can try it out.

Has anyone else tested this? Looks promising.

---

## [p10] Ayylmao420 — 2020-04-22T18:59:50Z

On 4/22/2020 at 8:50 PM, Gabriel Ramuglia said:

Thanks for the link. I'll see if I can try it out.

Has anyone else tested this? Looks promising.

tried it in the past, no difference, also, mouse movement doesn't have anything to do with bans, there are ppl running packet p(b)ots 24/7 and maybe see 1-2 bans per month

 Edited April 24, 2020 by Ayylmao420

---

## [p10] Protoprize — 2020-04-22T19:16:01Z

On 4/22/2020 at 8:50 PM, Gabriel Ramuglia said:

Thanks for the link. I'll see if I can try it out.

Has anyone else tested this? Looks promising.

I've tested a similar repo couple of days ago. Very do'able but you'd basically have to re create a lot of functions that are already included in the OSBot API. My acc's just been banned so imma be testing a lot until my IP changes in a couple of days  IT's still mostly just down to script design in the end anyway 

 Edited April 22, 2020 by Protoprize

---

## [p10] null0x1 — 2020-04-24T01:49:15Z

On 4/22/2020 at 8:59 PM, Ayylmao420 said:

tried it in the past, no difference, also, mouse movement doesn't have anything to do with bans, there are ppl running packet pots 24/7 and maybe see 1-2 bans per month

Packet pots?

---

## [p10] Naked — 2020-04-24T01:54:44Z

On 4/24/2020 at 3:49 AM, null0x1 said:

Packet pots?

Packet bots

---

## [p10] Gabriel Ramuglia — 2020-04-25T01:41:46Z

I wrote a script that makes soft clay out of banked clay (F2P) -- got banned almost immediately. Very click heavy.

Going to try again with breaks turned on -- it went for a couple hours straight last time.

Just seems to me the kind of bot is very mouse and click heavy would benefit from less obviously fake movements.

Bot didn't run "full speed" -- there were random short delays built in, with occasional long delays as well.

---

## [p10] null0x1 — 2020-04-26T21:51:07Z

Interesting thread/read here.

---

## [p10] Gabriel Ramuglia — 2020-04-27T19:35:23Z

On 4/25/2020 at 3:41 AM, Gabriel Ramuglia said:

I wrote a script that makes soft clay out of banked clay (F2P) -- got banned almost immediately. Very click heavy.

Going to try again with breaks turned on -- it went for a couple hours straight last time.

Just seems to me the kind of bot is very mouse and click heavy would benefit from less obviously fake movements.

Bot didn't run "full speed" -- there were random short delays built in, with occasional long delays as well.

Note: Just realized that the impacted accounts were probably already "delay banned" due to botting tutorial island. So not clear how quick they would have gotten banned from this otherwise.

---

## [p10] datpigeon — 2020-04-28T07:30:32Z

Wow going back and reading this makes me really sad to see the amount of gaslighting going on in an attempt to distract from the point OP was trying to make by creating this thread

---

## [p11] Gunman — 2020-04-28T07:53:54Z

On 4/28/2020 at 9:30 AM, datpigeon said:

Wow going back and reading this makes me really sad to see the amount of gaslighting going on in an attempt to distract from the point OP was trying to make by creating this thread

Idk if this thread is what sparked the new mouse algorithms development, because I know Pat did say they were discussing changing it farther up in the threads

---

