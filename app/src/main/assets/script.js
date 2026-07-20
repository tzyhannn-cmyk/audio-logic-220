let currentAudioUrl = "";
let currentVideoUrl = "";
let currentTitle = "";
let currentArtist = "";

function switchTab(tabId, el) {
    document.querySelectorAll('.tab-content').forEach(t => t.style.display = 'none');
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    
    document.getElementById(tabId).style.display = 'block';
    el.classList.add('active');

    if (tabId === 'videoTab' && currentVideoUrl !== "") {
        AndroidBridge.pauseAudioNative();
        let vPlayer = document.getElementById("videoPlayer");
        if(vPlayer.src !== currentVideoUrl) {
            vPlayer.src = currentVideoUrl;
        }
    }
}

function lakukanPencarian() {
    let query = document.getElementById("txtCari").value;
    if (!query) return alert("Masukkan kata kunci pencarian!");

    document.getElementById("loadingContainer").style.display = "block";
    document.getElementById("loadingText").innerText = "Mencari video teratas...";
    document.getElementById("searchResultList").innerHTML = "";

    AndroidBridge.searchYouTube(query);
}

function onSearchSuccess(jsonString) {
    document.getElementById("loadingContainer").style.display = "none";
    let videoList = JSON.parse(jsonString);
    let container = document.getElementById("searchResultList");

    if (videoList.length === 0) {
        container.innerHTML = "<p style='color:#aaa; font-size:14px;'>Video tidak ditemukan.</p>";
        return;
    }

    videoList.forEach(video => {
        let card = document.createElement("div");
        card.className = "video-card";
        card.innerHTML = `
            <img src="${video.thumbnail}" alt="thumb">
            <div class="video-card-info">
                <h4>${video.title}</h4>
                <p>${video.uploader}</p>
            </div>
        `;

        card.onclick = function() {
            document.getElementById("loadingContainer").style.display = "block";
            document.getElementById("loadingText").innerText = "Mengekstrak audio...";
            AndroidBridge.extractYouTube(video.url);
        };

        container.appendChild(card);
    });
}

function onExtractionSuccess(jsonString) {
    document.getElementById("loadingContainer").style.display = "none";
    let data = JSON.parse(jsonString);

    currentAudioUrl = data.audioUrl;
    currentVideoUrl = data.videoUrl;
    currentTitle = data.title;
    currentArtist = data.uploader;

    document.getElementById("txtJudul").innerText = data.title;
    document.getElementById("txtUploader").innerText = data.uploader;
    document.getElementById("imgThumbnail").src = data.thumbnail;
    document.getElementById("metaCard").style.display = "flex";

    switchTab('audioTab', document.querySelectorAll('.tab-btn')[1]);
    AndroidBridge.playAudioNative(currentAudioUrl, currentTitle, currentArtist);
}

function onLocalFileSelected(uri, name) {
    currentAudioUrl = uri;
    currentTitle = name;
    currentArtist = "Penyimpanan Internal";
    currentVideoUrl = "";

    document.getElementById("txtJudul").innerText = name;
    document.getElementById("txtUploader").innerText = "Lagu Lokal";
    document.getElementById("imgThumbnail").src = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=150"; 
    document.getElementById("metaCard").style.display = "flex";

    switchTab('audioTab', document.querySelectorAll('.tab-btn')[1]);
    AndroidBridge.playAudioNative(currentAudioUrl, currentTitle, currentArtist);
}

function onSearchFailed(error) {
    document.getElementById("loadingContainer").style.display = "none";
    alert("Pencarian Gagal: " + error);
}

function onExtractionFailed(error) {
    document.getElementById("loadingContainer").style.display = "none";
    alert("Ekstraksi Gagal: " + error);
}
