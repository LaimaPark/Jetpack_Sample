package com.laima.camerasample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import kotlinx.coroutines.launch

private const val PERMISSIONS_REQUEST_CODE = 10
private val PERMISSIONS_REQUIRED = mutableListOf(
    // 필요한 퍼미션들 리스트
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
).apply {
    // 이런 식으로 버전 체크도 가능
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}.toTypedArray()

class PermissionsFragment : Fragment() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (allPermissionsGranted()) {
            navigateToCamera()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                PERMISSIONS_REQUIRED,
                PERMISSIONS_REQUEST_CODE)
        }

    }

    private fun navigateToCamera() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    PermissionsFragmentDirections.actionPermissionsToCamera())
            }
        }

    }



    // Permission
    private fun allPermissionsGranted() = PERMISSIONS_REQUIRED.all {
        ContextCompat.checkSelfPermission(
            requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults:
        IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                navigateToCamera()
            } else {
                Toast.makeText(requireContext(),
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                requireActivity().finish()
            }
        }
    }


}